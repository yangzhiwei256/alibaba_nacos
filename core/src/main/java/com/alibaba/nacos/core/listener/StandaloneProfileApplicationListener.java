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

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Profile;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.Arrays;

import static com.alibaba.nacos.core.utils.CoreConstants.STANDALONE_MODE_PROPERTY_NAME;
import static com.alibaba.nacos.core.utils.CoreConstants.STANDALONE_SPRING_PROFILE;
import static com.alibaba.nacos.core.utils.SystemUtils.STANDALONE_MODE;

/**
 * Standalone {@link Profile} {@link ApplicationListener} for {@link ApplicationEnvironmentPreparedEvent}
 *
 * @author <a href="mailto:mercyblitz@gmail.com">Mercy</a>
 * @see ConfigurableEnvironment#addActiveProfile(String)
 * @since 0.2.2
 */
@Slf4j
public class StandaloneProfileApplicationListener implements ApplicationListener<ApplicationEnvironmentPreparedEvent>,
    PriorityOrdered {

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {

        ConfigurableEnvironment environment = event.getEnvironment();

        if (environment.getProperty(STANDALONE_MODE_PROPERTY_NAME, boolean.class, false)) {
            environment.addActiveProfile(STANDALONE_SPRING_PROFILE);
        }

        if (log.isInfoEnabled()) {
            log.info("Spring Environment's active profiles : {} in standalone mode : {}",
                Arrays.asList(environment.getActiveProfiles()),
                STANDALONE_MODE
            );
        }

    }

    @Override
    public int getOrder() {
        return HIGHEST_PRECEDENCE;
    }
}
