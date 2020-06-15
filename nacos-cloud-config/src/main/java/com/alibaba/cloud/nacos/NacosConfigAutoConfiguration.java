/*
 * Copyright (C) 2018 the original author or authors.
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

package com.alibaba.cloud.nacos;

import com.alibaba.cloud.nacos.refresh.NacosContextRefresher;
import com.alibaba.cloud.nacos.refresh.NacosRefreshHistory;
import com.alibaba.cloud.nacos.refresh.NacosRefreshProperties;
import com.alibaba.nacos.api.config.annotation.NacosValue;
import com.alibaba.nacos.client.config.listener.impl.NacosValueAnnotationBeanPostProcessor;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author juven.xuxb
 */
@Configuration
@ConditionalOnProperty(name = "spring.cloud.nacos.config.enabled", matchIfMissing = true)
public class NacosConfigAutoConfiguration {

	@Bean
	public NacosConfigProperties nacosConfigProperties(ApplicationContext context) {
		if (context.getParent() != null
				&& BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
						context.getParent(), NacosConfigProperties.class).length > 0) {
			return BeanFactoryUtils.beanOfTypeIncludingAncestors(context.getParent(),
					NacosConfigProperties.class);
		}
		return new NacosConfigProperties();
	}

	@Bean
    @ConditionalOnMissingBean
	public NacosRefreshProperties nacosRefreshProperties() {
		return new NacosRefreshProperties();
	}

	@Bean
    @ConditionalOnMissingBean
	public NacosRefreshHistory nacosRefreshHistory() {
		return new NacosRefreshHistory();
	}

	@Bean
	public NacosContextRefresher nacosContextRefresher(NacosConfigProperties configProperties,
                                                       NacosRefreshProperties nacosRefreshProperties,NacosRefreshHistory refreshHistory) {
		return new NacosContextRefresher(nacosRefreshProperties, refreshHistory, configProperties.configServiceInstance());
	}

    /**
     * Nacos Value注解处理器
     * @return
     */
	@Bean
    @ConditionalOnMissingBean
	public NacosValueAnnotationBeanPostProcessor nacosValueAnnotationBeanPostProcessor(){
	    return new NacosValueAnnotationBeanPostProcessor(NacosValue.class);
    }
}
