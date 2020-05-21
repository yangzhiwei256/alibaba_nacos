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
package com.alibaba.nacos.core.utils;

/**
 * Nacos common constants
 *
 * @author <a href="mailto:mercyblitz@gmail.com">Mercy</a>
 * @since 0.2.2
 */
public interface CoreConstants {

    /**
     * Spring Profile : "standalone"
     */
    String STANDALONE_SPRING_PROFILE = "standalone";

    /**
     * The System property name of  Standalone mode
     */
    String STANDALONE_MODE_PROPERTY_NAME = "nacos.standalone";
    String STANDALONE_MODE_ALONE = "standalone";
    String STANDALONE_MODE_CLUSTER = "cluster";
    String FUNCTION_MODE_CONFIG = "config";
    String FUNCTION_MODE_NAMING = "naming";

    /**
     * The System property name of  Function mode
     */
    String FUNCTION_MODE_PROPERTY_NAME = "nacos.functionMode";

    /**
     * The System property name of  Nacos Home
     */
    String NACOS_HOME = "nacos.home";

    /**
     * The System property name of prefer hostname over ip
     */
    String PREFER_HOSTNAME_OVER_IP_PROPERTY_NAME = "nacos.preferHostnameOverIp";

    /**
     * the root context path
     */
    String ROOT_WEB_CONTEXT_PATH = "/";

    String NACOS_SERVER_IP = "nacos.server.ip";

    String USE_ONLY_SITE_INTERFACES = "nacos.inetutils.use-only-site-local-interfaces";
    String PREFERRED_NETWORKS = "nacos.inetutils.preferred-networks";
    String IGNORED_INTERFACES = "nacos.inetutils.ignored-interfaces";
    String IP_ADDRESS = "nacos.inetutils.ip-address";
    String PREFER_HOSTNAME_OVER_IP = "nacos.inetutils.prefer-hostname-over-ip";
    String SYSTEM_PREFER_HOSTNAME_OVER_IP = "nacos.preferHostnameOverIp";
    String WEB_CONTEXT_PATH = "server.servlet.context-path";
    String COMMA_DIVISION = ",";

    String NACOS_SERVER_HEADER = "Nacos-Server";

    //nacos集群配置文件名
    String NACOS_CONF_FILE_NAME = "cluster.conf";

    //nacos cluster.conf 指定全路径 -D传递
    String NACOS_CONF_FILE_PATH = "nacos.conf";

    //默认 nacos配置目录： 相对nacos.home目录
    String NACOS_CONF_FILE_DIR = "conf";
}
