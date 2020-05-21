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

import com.alibaba.nacos.common.utils.IoUtils;
import com.sun.management.OperatingSystemMXBean;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.alibaba.nacos.core.utils.CoreConstants.FUNCTION_MODE_PROPERTY_NAME;
import static com.alibaba.nacos.core.utils.CoreConstants.STANDALONE_MODE_PROPERTY_NAME;
import static org.apache.commons.lang3.CharEncoding.UTF_8;

/**
 * @author nacos
 */
@Slf4j
public class SystemUtils {

    /**
     * Standalone mode or not
     */
    public static final boolean STANDALONE_MODE = Boolean.getBoolean(STANDALONE_MODE_PROPERTY_NAME);

    /**
     * server
     */
    public static final String FUNCTION_MODE = System.getProperty(FUNCTION_MODE_PROPERTY_NAME);


    private static final OperatingSystemMXBean operatingSystemMXBean = (OperatingSystemMXBean) ManagementFactory
        .getOperatingSystemMXBean();

    /**
     * nacos local ip
     */
    public static final String LOCAL_IP = InetUtils.getSelfIp();

    public static List<String> getIPsBySystemEnv(String key) {
        String env = getSystemEnv(key);
        List<String> ips = new ArrayList<>();
        if (StringUtils.isNotEmpty(env)) {
            ips = Arrays.asList(env.split(","));
        }
        return ips;
    }

    public static String getSystemEnv(String key) {
        return System.getenv(key);
    }

    public static float getLoad() {
        return (float) operatingSystemMXBean.getSystemLoadAverage();
    }

    public static float getCPU() {
        return (float) operatingSystemMXBean.getSystemCpuLoad();
    }

    public static float getMem() {
        return (float) (1 - (double) operatingSystemMXBean.getFreePhysicalMemorySize() / (double) operatingSystemMXBean
            .getTotalPhysicalMemorySize());
    }

    public static String getNacosHomePath() {
        String nacosHome = System.getProperty(CoreConstants.NACOS_HOME);
        if (StringUtils.isEmpty(nacosHome)) {
            nacosHome = System.getProperty("user.dir") + File.separator + "nacos";
        }
        log.info("nacos home dir path: {}", nacosHome);
        return nacosHome;
    }

    /**
     * The file path of cluster conf.
     */
    public static String getClusterConfFilePath() {
        String confPath = System.getProperty(CoreConstants.NACOS_CONF_FILE_PATH);
        if(StringUtils.isEmpty(confPath)){
            confPath = getNacosHomePath() + File.separator + CoreConstants.NACOS_CONF_FILE_DIR + File.separator + CoreConstants.NACOS_CONF_FILE_NAME;
        }
        File file = new File(confPath);
        if(!file.exists() || file.isDirectory()){
            confPath = System.getProperty("user.dir");
        }
        log.info("nacos cluster config file path: {}", confPath);
        return confPath;
    }

    public static List<String> readClusterConf() throws IOException {
        List<String> instanceList = new ArrayList<String>();
        try(Reader reader = new InputStreamReader(new FileInputStream(new File(getClusterConfFilePath())),
        StandardCharsets.UTF_8)) {
            List<String> lines = IoUtils.readLines(reader);
            String comment = "#";
            for (String line : lines) {
                String instance = line.trim();
                if (instance.startsWith(comment)) {
                    continue;
                }
                if (instance.contains(comment)) {
                    instance = instance.substring(0, instance.indexOf(comment));
                    instance = instance.trim();
                }
                int multiIndex = instance.indexOf(CoreConstants.COMMA_DIVISION);
                if (multiIndex > 0) {
                    instanceList.addAll(Arrays.asList(instance.split(CoreConstants.COMMA_DIVISION)));
                } else {
                    instanceList.add(instance);
                }
            }
            return instanceList;
        }
    }

    public static void writeClusterConf(String content) throws IOException {
        IoUtils.writeStringToFile(new File(getClusterConfFilePath()), content, UTF_8);
    }

}
