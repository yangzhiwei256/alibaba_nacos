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
package com.alibaba.nacos.core.listener;

import com.alibaba.nacos.core.utils.CoreConstants;
import com.alibaba.nacos.core.utils.SystemUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringApplicationRunListener;
import org.springframework.boot.context.event.EventPublishingRunListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Logging starting message {@link SpringApplicationRunListener} before {@link EventPublishingRunListener} execution
 *
 * @author <a href="mailto:huangxiaoyu1018@gmail.com">hxy1991</a>
 * @since 0.5.0
 */
@Slf4j
public class StartingSpringApplicationRunListener implements SpringApplicationRunListener, Ordered {

    private static final String MODE_PROPERTY_KEY_STAND_MODE = "nacos.mode";

    private static final String MODE_PROPERTY_KEY_FUNCTION_MODE = "nacos.function.mode";

    private static final String LOCAL_IP_PROPERTY_KEY = "nacos.local.ip";

    private ScheduledExecutorService scheduledExecutorService;

    private volatile boolean starting;

    public StartingSpringApplicationRunListener(SpringApplication application, String[] args) {

    }

    @Override
    public void starting() {
        starting = true;
    }

    @Override
    public void environmentPrepared(ConfigurableEnvironment environment) {
        if (SystemUtils.STANDALONE_MODE) {
            System.setProperty(MODE_PROPERTY_KEY_STAND_MODE, "stand alone");
        } else {
            System.setProperty(MODE_PROPERTY_KEY_STAND_MODE, "cluster");
        }
        if (SystemUtils.FUNCTION_MODE == null) {
           System.setProperty(MODE_PROPERTY_KEY_FUNCTION_MODE, "All");
        } else if(CoreConstants.FUNCTION_MODE_CONFIG.equals(SystemUtils.FUNCTION_MODE)){
            System.setProperty(MODE_PROPERTY_KEY_FUNCTION_MODE, CoreConstants.FUNCTION_MODE_CONFIG);
        } else if(CoreConstants.FUNCTION_MODE_NAMING.equals(SystemUtils.FUNCTION_MODE)) {
            System.setProperty(MODE_PROPERTY_KEY_FUNCTION_MODE, CoreConstants.FUNCTION_MODE_NAMING);
        }
        System.setProperty(LOCAL_IP_PROPERTY_KEY, SystemUtils.LOCAL_IP);
    }

    @Override
    public void contextPrepared(ConfigurableApplicationContext context) {
        logClusterConf();
        logStarting();
    }

    @Override
    public void contextLoaded(ConfigurableApplicationContext context) {

    }

    @Override
    public void started(ConfigurableApplicationContext context) {
        starting = false;
        if (scheduledExecutorService != null) {
            scheduledExecutorService.shutdownNow();
        }
        logFilePath();
        log.info("Nacos started successfully in {} mode.", System.getProperty(MODE_PROPERTY_KEY_STAND_MODE));
    }

    @Override
    public void running(ConfigurableApplicationContext context) {

    }

    @Override
    public void failed(ConfigurableApplicationContext context, Throwable exception) {
        starting = false;
        logFilePath();
        log.error("Nacos failed to start, please see {}/logs/nacos-root.log for more details.", SystemUtils.getNacosHomePath());
    }

    /**
     * Before {@link EventPublishingRunListener}
     *
     * @return HIGHEST_PRECEDENCE
     */
    @Override
    public int getOrder() {
        return HIGHEST_PRECEDENCE;
    }

    private void logClusterConf() {
        if (!SystemUtils.STANDALONE_MODE) {
            try {
                List<String> clusterConf = SystemUtils.readClusterConf();
                log.info("The server IP list of Nacos is {}", clusterConf);
            } catch (IOException e) {
                log.error("read cluster conf fail", e);
            }
        }
    }

    private void logFilePath() {
        String[] dirNames = new String[]{"logs", "conf", "data"};
        for (String dirName: dirNames) {
            log.info("Nacos {} files: {}{}{}{}", dirName, SystemUtils.getNacosHomePath(), File.separatorChar, dirName, File.separatorChar);
        }
    }

    private void logStarting() {
        if (!SystemUtils.STANDALONE_MODE) {

            scheduledExecutorService = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r, "nacos-starting");
                    thread.setDaemon(true);
                    return thread;
                }
            });

            scheduledExecutorService.scheduleWithFixedDelay(new Runnable() {
                @Override
                public void run() {
                    if (starting) {
                        log.info("Nacos is starting...");
                    }
                }
            }, 1, 1, TimeUnit.SECONDS);
        }
    }
}
