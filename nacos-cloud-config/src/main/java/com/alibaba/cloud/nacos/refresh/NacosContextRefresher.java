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

package com.alibaba.cloud.nacos.refresh;

import com.alibaba.cloud.nacos.NacosPropertySourceRepository;
import com.alibaba.cloud.nacos.client.NacosPropertySource;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.ConfigListener;
import com.alibaba.nacos.api.exception.NacosException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cloud.endpoint.event.RefreshEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.util.StringUtils;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * On application start up, NacosContextRefresher add nacos listeners to all application
 * level dataIds, when there is a change in the data, listeners will refresh
 * configurations.
 *
 * @author juven.xuxb
 * @author pbting
 */
@Slf4j
public class NacosContextRefresher implements ApplicationListener<ApplicationReadyEvent>, ApplicationContextAware {

	private static final AtomicLong REFRESH_COUNT = new AtomicLong(0);

	private final NacosRefreshProperties refreshProperties;

	private final NacosRefreshHistory refreshHistory;

	private final ConfigService configService;

	private ApplicationContext applicationContext;

	private final AtomicBoolean ready = new AtomicBoolean(false);

	/** nacos配置监听器缓存 **/
	private final Map<String, ConfigListener> listenerMap = new ConcurrentHashMap<>(16);

	public NacosContextRefresher(NacosRefreshProperties refreshProperties, NacosRefreshHistory refreshHistory, ConfigService configService) {
		this.refreshProperties = refreshProperties;
		this.refreshHistory = refreshHistory;
		this.configService = configService;
	}

	@Override
	public void onApplicationEvent(ApplicationReadyEvent event) {
		// many Spring context
		if (this.ready.compareAndSet(false, true)) {
			this.registerNacosListenersForApplications();
		}
	}

	@Override
	public void setApplicationContext(ApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
	}

    /**
     * nacos 注册配置监听器
     */
	private void registerNacosListenersForApplications() {

	    //默认开启自动加载机制
		if (refreshProperties.isEnabled()) {
			for (NacosPropertySource nacosPropertySource : NacosPropertySourceRepository.getAll()) {
				if (!nacosPropertySource.isRefreshable()) {
					continue;
				}
				String dataId = nacosPropertySource.getDataId();
				registerNacosListener(nacosPropertySource.getGroup(), dataId);
			}
		}
	}

    /**
     * 注册nacos配置监听器
     * @param group 组名
     * @param dataId 数据ID
     */
	private void registerNacosListener(final String group, final String dataId) {

		ConfigListener configListener = listenerMap.computeIfAbsent(dataId, i -> new ConfigListener() {
			@Override
			public void receiveConfigInfo(String configInfo) {
				refreshCountIncrement();
				String md5 = "";
				if (!StringUtils.isEmpty(configInfo)) {
					try {
						MessageDigest md = MessageDigest.getInstance("MD5");
						md5 = new BigInteger(1, md.digest(configInfo.getBytes(StandardCharsets.UTF_8))).toString(16);
					}
					catch (NoSuchAlgorithmException e) {
						log.warn("[Nacos] unable to get md5 for dataId: " + dataId, e);
					}
				}
				refreshHistory.add(dataId, md5);

				//发布配置刷新事件
				applicationContext.publishEvent(new RefreshEvent(this, null, "Refresh Nacos config"));
				if (log.isDebugEnabled()) {
					log.debug("Refresh Nacos config group " + group + ",dataId" + dataId);
				}
			}

			@Override
			public Executor getExecutor() {
				return null;
			}
		});

		try {
			configService.addListener(dataId, group, configListener);
		}
		catch (NacosException e) {
			e.printStackTrace();
		}
	}

	public static long getRefreshCount() {
		return REFRESH_COUNT.get();
	}

	public static void refreshCountIncrement() {
		REFRESH_COUNT.incrementAndGet();
	}
}
