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
package com.alibaba.nacos.client.config.listener.impl;

import com.alibaba.nacos.api.config.listener.AbstractListener;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.StringReader;
import java.util.Properties;

/**
 * Properties Listener
 *
 * @author Nacos
 */
@SuppressWarnings("PMD.AbstractClassShouldStartWithAbstractNamingRule")
@Slf4j
public abstract class PropertiesListener extends AbstractListener {

    @Override
    public void receiveConfigInfo(String configInfo) {
        if (StringUtils.isEmpty(configInfo)) {
            return;
        }

        Properties properties = new Properties();
        try {
            properties.load(new StringReader(configInfo));
            innerReceive(properties);
        } catch (IOException e) {
            log.error("load properties error：" + configInfo, e);
        }

    }

    /**
     * properties type for receiver
     *
     * @param properties properties
     */
    public abstract void innerReceive(Properties properties);

}
