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

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;

/**
 * datasource interface
 *
 * @author Nacos
 */
public interface DataSourceService {

    /**
     * 刷新数据源
     * @throws IOException exception
     */
    void reload() throws IOException;

    /**
     * 检查是否是master db
     * @return is master
     */
    boolean checkMasterWritable();

    /**
     * 获取JDBC模板
     * @return JdbcTemplate
     */
    JdbcTemplate getJdbcTemplate();

    /**
     * 获取事务模板
     * @return TransactionTemplate
     */
    TransactionTemplate getTransactionTemplate();

    /**
     * 获取数据库路径
     * @return
     */
    String getCurrentDBUrl();

    /**
     * 检查数据库健康
     * @return heath info
     */
    String getHealth();
}
