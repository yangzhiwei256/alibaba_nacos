/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.nacos.config.server.service;

import com.alibaba.nacos.config.server.config.DataSourceConfigProperties;
import com.alibaba.nacos.config.server.config.DatabaseProperties;
import com.alibaba.nacos.config.server.monitor.MetricsMonitor;
import com.alibaba.nacos.config.server.utils.PropertyUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.dbcp.BasicDataSource;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.CollectionUtils;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.alibaba.nacos.config.server.service.PersistService.CONFIG_INFO4BETA_ROW_MAPPER;
import static com.alibaba.nacos.core.utils.SystemUtils.STANDALONE_MODE;

/**
 * 基础数据源配置
 * Base data source
 * @author Nacos
 */
@Service("basicDataSourceService")
@Slf4j
public class BasicDataSourceServiceImpl implements DataSourceService {

    private static final String DEFAULT_POSTGRESQL_DRIVER = "org.postgresql.Driver";
    private static String JDBC_DRIVER_NAME;

    /**
     * JDBC执行超时时间, 单位秒
     */
    private int queryTimeout = 10;
    private static final int TRANSACTION_QUERY_TIMEOUT = 10;

    private static final String DB_LOAD_ERROR_MSG = "[db-load-error]load jdbc.properties error";

    /** 数据源集合 **/
    private List<BasicDataSource> dataSourceList = new ArrayList<>();

    /** 主库 dataSourceList 数组索引**/
    private volatile int masterIndex;

    private JdbcTemplate jdbcTemplate;
    private DataSourceTransactionManager dataSourceTransactionManager;
    private TransactionTemplate transactionTemplate;

    private JdbcTemplate testMasterJdbcTemplate;
    private JdbcTemplate testMasterWritableJdbcTemplate;

    volatile private List<JdbcTemplate> jdbcTemplateList;
    volatile private List<Boolean> isHealthList;
    private static final Pattern ipPattern = Pattern.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");

    @Autowired
    private DataSourceConfigProperties dataSourceConfigProperties;

    @Autowired
    private Environment env;

    static {
        try {
            Class.forName(DEFAULT_POSTGRESQL_DRIVER);
            JDBC_DRIVER_NAME = DEFAULT_POSTGRESQL_DRIVER;
            log.info("Use PostgreSQL as the driver");
        } catch (ClassNotFoundException e) {
            log.error("POSTGRESQL数据库驱动【{}】不存在",DEFAULT_POSTGRESQL_DRIVER, e);
        }
    }

    @PostConstruct
    public void init() {
        queryTimeout = NumberUtils.toInt(System.getProperty("QUERYTIMEOUT"), 3);
        jdbcTemplate = new JdbcTemplate();
        jdbcTemplate.setMaxRows(50000); //预防内存膨胀，设置最大查询数
        jdbcTemplate.setQueryTimeout(queryTimeout); //设置查询超时

        testMasterJdbcTemplate = new JdbcTemplate();
        testMasterJdbcTemplate.setQueryTimeout(queryTimeout);

        testMasterWritableJdbcTemplate = new JdbcTemplate();
        testMasterWritableJdbcTemplate.setQueryTimeout(1);
        /**
         * 数据库健康检测
         */
        jdbcTemplateList = new ArrayList<>();
        isHealthList = new ArrayList<>();

        dataSourceTransactionManager = new DataSourceTransactionManager();
        transactionTemplate = new TransactionTemplate(dataSourceTransactionManager);
        /**
         *  事务的超时时间需要与普通操作区分开
         */
        transactionTemplate.setTimeout(TRANSACTION_QUERY_TIMEOUT);
        if (!STANDALONE_MODE || PropertyUtil.isStandaloneUseMysql()) {
            try {
                reload();
            } catch (IOException e) {
                e.printStackTrace();
                throw new RuntimeException(DB_LOAD_ERROR_MSG);
            }

            //选择数据库主库URL 定时任务
            TimerTaskService.scheduleWithFixedDelay(new SelectMasterTask(), 10, 10,TimeUnit.SECONDS);

            //定时检查数据库健康任务
            TimerTaskService.scheduleWithFixedDelay(new CheckDBHealthTask(), 10, 10,TimeUnit.SECONDS);
        }
    }

    @Override
    public synchronized void reload() throws IOException {

        try {
            if(CollectionUtils.isEmpty(dataSourceConfigProperties.getDb())){
                throw new IllegalArgumentException("database config is null");
            }

            List<BasicDataSource> basicDataSourceArrayList = new ArrayList<>();
            for (int i = 0; i < dataSourceConfigProperties.getDb().size(); i++) {
                DatabaseProperties databaseProperties = dataSourceConfigProperties.getDb().get(i);
                BasicDataSource basicDataSource = new BasicDataSource();
                basicDataSource.setDriverClassName(JDBC_DRIVER_NAME);

                //URL配置
                if (StringUtils.isEmpty(databaseProperties.getUrl())) {
                    log.error("db.url." + i + " is null");
                    throw new IllegalArgumentException();
                }
                basicDataSource.setUrl(databaseProperties.getUrl());

                //username配置
                if (StringUtils.isEmpty(databaseProperties.getUsername()) && StringUtils.isEmpty(dataSourceConfigProperties.getUsername())) {
                    log.error("db.username." + i + " is null");
                    throw new IllegalArgumentException();
                }
                basicDataSource.setUsername(StringUtils.isEmpty(databaseProperties.getUsername()) ? dataSourceConfigProperties.getUsername() : databaseProperties.getUsername());

                //密码配置
                if (StringUtils.isEmpty(databaseProperties.getPassword()) && StringUtils.isEmpty(dataSourceConfigProperties.getPassword())) {
                    log.error("db.password." + i + " is null");
                    throw new IllegalArgumentException();
                }
                basicDataSource.setPassword(StringUtils.isEmpty(databaseProperties.getPassword()) ? dataSourceConfigProperties.getPassword() : databaseProperties.getPassword());
                basicDataSource.setInitialSize(dataSourceConfigProperties.getInitialSize());
                basicDataSource.setMaxActive(dataSourceConfigProperties.getMaxActive());
                basicDataSource.setMaxIdle(dataSourceConfigProperties.getMaxIdle());
                basicDataSource.setMaxWait(dataSourceConfigProperties.getMaxWait());
                basicDataSource.setPoolPreparedStatements(true);

                // 每10分钟检查一遍连接池
                basicDataSource.setTimeBetweenEvictionRunsMillis(TimeUnit.MINUTES.toMillis(10L));
                basicDataSource.setTestWhileIdle(true);
                basicDataSource.setValidationQuery("SELECT 1 FROM dual");
                basicDataSourceArrayList.add(basicDataSource);

                JdbcTemplate jdbcTemplate = new JdbcTemplate();
                jdbcTemplate.setQueryTimeout(queryTimeout);
                jdbcTemplate.setDataSource(basicDataSource);

                jdbcTemplateList.add(jdbcTemplate);
                isHealthList.add(Boolean.TRUE);
            }

            if (CollectionUtils.isEmpty(basicDataSourceArrayList)) {
                throw new RuntimeException("no datasource available");
            }

            dataSourceList = basicDataSourceArrayList;

            //选择数据库集群主节点定时任务
            new SelectMasterTask().run();

            //选择数据库集群健康检查任务
            new CheckDBHealthTask().run();
        } catch (RuntimeException e) {
            log.error(DB_LOAD_ERROR_MSG, e);
            throw new IOException(e);
        }
    }

    @Override
    public boolean checkMasterWritable() {
        testMasterWritableJdbcTemplate.setDataSource(jdbcTemplate.getDataSource());
        testMasterWritableJdbcTemplate.setQueryTimeout(1);
        String sql = "SELECT @@read_only ";
        try {
            Integer result = testMasterWritableJdbcTemplate.queryForObject(sql, Integer.class);
            if (result == null) {
                return false;
            } else {
                return result == 0;
            }
        } catch (CannotGetJdbcConnectionException e) {
            log.error("[db-error] " + e.toString(), e);
            return false;
        }
    }

    @Override
    public JdbcTemplate getJdbcTemplate() {
        return this.jdbcTemplate;
    }

    @Override
    public TransactionTemplate getTransactionTemplate() {
        return this.transactionTemplate;
    }

    @Override
    public String getCurrentDBUrl() {
        DataSource ds = this.jdbcTemplate.getDataSource();
        if (ds == null) {
            return StringUtils.EMPTY;
        }
        BasicDataSource bds = (BasicDataSource) ds;
        return bds.getUrl();
    }

    @Override
    public String getHealth() {
        for (int i = 0; i < isHealthList.size(); i++) {
            if (!isHealthList.get(i)) {
                if (i == masterIndex) {
                    /**
                     * 主库不健康
                     */
                    return "DOWN:" + getIpFromUrl(dataSourceList.get(i).getUrl());
                } else {
                    /**
                     * 从库不健康
                     */
                    return "WARN:" + getIpFromUrl(dataSourceList.get(i).getUrl());
                }
            }
        }

        return "UP";
    }

    private String getIpFromUrl(String url) {

        Matcher m = ipPattern.matcher(url);
        if (m.find()) {
            return m.group();
        }

        return "";
    }

    static String defaultIfNull(String value, String defaultValue) {
        return null == value ? defaultValue : value;
    }

    /**
     * 获取数据库主库URL定时任务
     */
    private class SelectMasterTask implements Runnable {

        @Override
        public void run() {
            if (log.isDebugEnabled()) {
                log.debug("check master db.");
            }
            boolean isFound = false;
            int index = -1;
            for (BasicDataSource ds : dataSourceList) {
                index++;
                testMasterJdbcTemplate.setDataSource(ds);
                testMasterJdbcTemplate.setQueryTimeout(queryTimeout);
                try {
                    testMasterJdbcTemplate.update("DELETE FROM config_info WHERE data_id='com.alibaba.nacos.testMasterDB'");
                    if (jdbcTemplate.getDataSource() != ds) {
                        log.warn("[master-db] {}", ds.getUrl());
                    }
                    jdbcTemplate.setDataSource(ds);
                    dataSourceTransactionManager.setDataSource(ds);
                    isFound = true;
                    masterIndex = index;
                    break;
                } catch (DataAccessException e) { // read only
                    e.printStackTrace();
                }
            }
            if (!isFound) {
                log.error("[master-db] master db not found.");
                MetricsMonitor.getDbException().increment();
            }
        }
    }

    /**
     * 检查数据库健康定时任务
     */
    @SuppressWarnings("PMD.ClassNamingShouldBeCamelRule")
    private class CheckDBHealthTask implements Runnable {

        @Override
        public void run() {
            if (log.isDebugEnabled()) {
                log.debug("check db health.");
            }
            String sql = "SELECT * FROM config_info_beta WHERE id = 1";
            for (int i = 0; i < jdbcTemplateList.size(); i++) {
                JdbcTemplate jdbcTemplate = jdbcTemplateList.get(i);
                try {
                    jdbcTemplate.query(sql, CONFIG_INFO4BETA_ROW_MAPPER);
                    isHealthList.set(i, Boolean.TRUE);
                } catch (DataAccessException e) {
                    if (i == masterIndex) {
                        log.error("[db-error] master db {} down.", getIpFromUrl(dataSourceList.get(i).getUrl()));
                    } else {
                        log.error("[db-error] slave db {} down.", getIpFromUrl(dataSourceList.get(i).getUrl()));
                    }
                    isHealthList.set(i, Boolean.FALSE);
                    MetricsMonitor.getDbException().increment();
                }
            }
        }
    }
}
