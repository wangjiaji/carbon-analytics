/*
 *  Copyright (c) 2016 WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */
package org.wso2.carbon.analytics.engine.core;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.spark.SparkConf;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.util.Utils;
import org.wso2.carbon.analytics.data.commons.AnalyticsEngine;
import org.wso2.carbon.analytics.data.commons.AnalyticsEngineQueryResult;
import org.wso2.carbon.analytics.data.commons.exception.AnalyticsException;
import org.wso2.carbon.analytics.data.commons.service.AnalyticsDataHolder;
import org.wso2.carbon.analytics.engine.commons.AnalyticsRelationProvider;
import org.wso2.carbon.analytics.engine.commons.AnalyzerEngineConstants;
import org.wso2.carbon.analytics.engine.commons.SparkAnalyticsEngineQueryResult;
import org.wso2.carbon.analytics.engine.exceptions.AnalyticsExecutionException;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.wso2.carbon.analytics.data.commons.sources.AnalyticsCommonConstants.ANALYTICS_CONF_DIR;

/**
 * Spark implementation of the Analytics Engine.
 */
public class SparkAnalyticsEngine implements AnalyticsEngine {
    private static final Log log = LogFactory.getLog(SparkAnalyticsEngine.class);

    private SparkSession sparkSession;
    private SparkConf sparkConf;
    private String sparkMaster;
    private String appName;
    private Map<String, String> shorthandStringsMap;
    private String sparkConfPath;

    // todo: get the paths properly
    private final String[] jarsToBeAdded = {
            "/tmp/jars/org.wso2.carbon.analytics.datasource.rdbms-6.0.0-SNAPSHOT.jar",
            "/tmp/jars/org.wso2.carbon.analytics.dataservice-6.0.0-SNAPSHOT.jar",
            "/tmp/jars/org.wso2.carbon.analytics.data.commons-6.0.0-SNAPSHOT.jar",
            "/tmp/jars/org.wso2.carbon.analytics.engine-6.0.0-SNAPSHOT.jar",
            "/tmp/jars/org.wso2.carbon.datasource.core-1.1.0.jar",
            "/tmp/jars/HikariCP-2.5.1.jar",
            "/tmp/jars/h2-1.4.187.jar",
            "/tmp/jars/kryo-shaded-3.0.3.jar"
    };
    private final String[] filesToBeAdded = {
            "/tmp/configs/rdbms-config.xml",
            "/tmp/configs/analytics-datasources.xml",
            "/tmp/configs/analytics-dataservice-config.xml"};

    public SparkAnalyticsEngine() {
        this.sparkConfPath = null;
        init();
    }

    public SparkAnalyticsEngine(String sparkConfFilePath) {
        this.sparkConfPath = sparkConfFilePath;
        init();
    }

    private void init() {
        this.shorthandStringsMap = new HashMap<>();
        registerAnalyticsProviders();
        try {
            initSparkConf();
        } catch (AnalyticsException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        //create the spark session
        this.sparkSession = SparkSession.builder().master(this.sparkMaster)
                .appName(appName)
                .config(sparkConf).getOrCreate();
        
        //adding the relevant jars todo: handle this properly
        for (int i = 0; i < jarsToBeAdded.length; i++) {
            this.sparkSession.sparkContext().addJar(jarsToBeAdded[i]);
        }

        // adding the conf files todo: handle these properly
        for (int j = 0; j < filesToBeAdded.length; j++) {
            this.sparkSession.sparkContext().addFile(filesToBeAdded[j]);
        }

        // set the analytics config directory
        this.sparkSession.conf().set(AnalyzerEngineConstants.SPARK_ANALYTICS_CONFIGS, "/tmp/configs");
        
    }

    /**
     * This method initializes the spark configurations.
     *
     * @throws AnalyticsException
     */
    private void initSparkConf() throws AnalyticsException {
        this.sparkConf = new SparkConf(false);
        String sparkConfFile = null;
        if (this.sparkConfPath != null) {
            sparkConfFile = this.sparkConfPath;
        } else {
            sparkConfFile = AnalyticsDataHolder.getInstance().getAnalyticsConfigsDir() + File.separator +
                    ANALYTICS_CONF_DIR + File.separator + AnalyzerEngineConstants.SPARK_CONF_FOLDER +
                    File.separator + AnalyzerEngineConstants.SPARK_CONF_FILE;
        }
        scala.collection.Map<String, String> properties = Utils.getPropertiesFromFile(sparkConfFile);
        this.sparkConf.setAll(properties);
        // setting spark master
        try {
            this.sparkMaster = sparkConf.get(AnalyzerEngineConstants.SPARK_MASTER);
        } catch (NoSuchElementException e) {
            this.sparkMaster = "local";
        }
        // setting the app name
        this.appName = sparkConf.get(AnalyzerEngineConstants.SPARK_APP_NAME);
    }

    @Override
    public AnalyticsEngineQueryResult executeQuery(String query) throws AnalyticsExecutionException {
        String processedQuery = replaceShorthandStrings(query);
        if (processedQuery.endsWith(";")) {
            processedQuery = processedQuery.substring(0, processedQuery.length() - 1).trim();
        }
        // todo: implement processing incremental queries
        // checkAndProcessIncrementalQuery();

        long start = System.currentTimeMillis();
        boolean success = true;
        AnalyticsEngineQueryResult analyticsEngineQueryResult = null;
        try {
            Dataset<Row> resultsSet = sparkSession.sql(processedQuery);
            analyticsEngineQueryResult = convertToResult(resultsSet);
        } catch (Throwable throwable) {
            success = false;
            throw new AnalyticsExecutionException("Exception in executing query " + query, throwable);
        } finally {
            // todo: add printing this based on -DenableAnalyticsStats
            long end = System.currentTimeMillis();
            if (success) {
                log.info("Executed query: " + query + " \nTime Elapsed: " + (end - start) / 1000.0 + " seconds.");
            } else {
                log.error("Unable to execute query: " + query + " \nTime Elapsed: " + (end - start) / 1000.0 +
                        " seconds.");
            }
        }
        return analyticsEngineQueryResult;
    }

    @Override
    public String getVersion() {
        return AnalyzerEngineConstants.SPARK_ANALYTICS_ENGINE_NAME + " : " + this.sparkSession.version();
    }

    private AnalyticsEngineQueryResult convertToResult(Dataset<Row> results) {
        int resultsLimit = this.sparkConf.getInt("carbon.spark.results.limit", -1);
        if (resultsLimit != -1) {
            return new SparkAnalyticsEngineQueryResult(results.schema().fieldNames(),
                    convertRowsToResult(results.limit(resultsLimit).collectAsList()));
        } else {
            return new SparkAnalyticsEngineQueryResult(results.schema().fieldNames(),
                    convertRowsToResult(results.collectAsList()));
        }
    }

    private List<List<Object>> convertRowsToResult(List<Row> rows) {
        List<List<Object>> result = new ArrayList<>();
        List<Object> objects;
        for (Row row : rows) {
            objects = new ArrayList<>();
            for (int i = 0; i < row.length(); i++) {
                objects.add(row.get(i));
            }
            result.add(objects);
        }
        return result;
    }

    private String replaceShorthandStrings(String query) {
        for (Map.Entry<String, String> entry : this.shorthandStringsMap.entrySet()) {
            query = query.replaceFirst("\\b" + entry.getKey() + "\\b", entry.getValue());
        }
        return query.trim();
    }

    private void registerAnalyticsProviders() {
        this.shorthandStringsMap.put(AnalyzerEngineConstants.SPARK_CARBONANALYTICS_PROVIDER,
                AnalyticsRelationProvider.class.getName());
    }
}
