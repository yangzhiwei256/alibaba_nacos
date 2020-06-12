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
package com.alibaba.nacos.client.config.impl;

import com.alibaba.nacos.api.common.Constants;
import com.alibaba.nacos.api.config.ConfigChangeEvent;
import com.alibaba.nacos.api.config.listener.AbstractSharedConfigListener;
import com.alibaba.nacos.api.config.listener.ConfigListener;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.client.config.filter.impl.ConfigFilterChainManager;
import com.alibaba.nacos.client.config.filter.impl.ConfigResponse;
import com.alibaba.nacos.client.config.listener.impl.AbstractConfigChangeConfigListener;
import com.alibaba.nacos.client.config.utils.MD5;
import com.alibaba.nacos.client.utils.TenantUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Nacos配置缓存数据
 * Listner Management
 * @author Nacos
 */
@Slf4j
public class CacheData {

    // ==================
    private final String name;
    private final ConfigFilterChainManager configFilterChainManager;
    public final String dataId;
    public final String group;
    public final String tenant;
    private final CopyOnWriteArrayList<ManagerListenerWrap> listeners;

    private volatile String md5;
    /**
     * whether use local config
     */
    private volatile boolean isUseLocalConfig = false;
    /**
     * last modify time
     */
    private volatile long localConfigLastModified;
    private volatile String content;
    private int taskId;

    /** 是否初始化 **/
    private volatile boolean isInitializing = true;
    private String type;

    public CacheData(ConfigFilterChainManager configFilterChainManager, String name, String dataId, String group) {
        if (null == dataId || null == group) {
            throw new IllegalArgumentException("dataId=" + dataId + ", group=" + group);
        }
        this.name = name;
        this.configFilterChainManager = configFilterChainManager;
        this.dataId = dataId;
        this.group = group;
        this.tenant = TenantUtil.getUserTenantForAcm();
        listeners = new CopyOnWriteArrayList<ManagerListenerWrap>();
        this.isInitializing = true;
        this.content = loadCacheContentFromDiskLocal(name, dataId, group, tenant);
        this.md5 = getMd5String(content);
    }

    public CacheData(ConfigFilterChainManager configFilterChainManager, String name, String dataId, String group,
                     String tenant) {
        if (null == dataId || null == group) {
            throw new IllegalArgumentException("dataId=" + dataId + ", group=" + group);
        }
        this.name = name;
        this.configFilterChainManager = configFilterChainManager;
        this.dataId = dataId;
        this.group = group;
        this.tenant = tenant;
        listeners = new CopyOnWriteArrayList<ManagerListenerWrap>();
        this.isInitializing = true;
        this.content = loadCacheContentFromDiskLocal(name, dataId, group, tenant);
        this.md5 = getMd5String(content);
    }

    public boolean isInitializing() {
        return isInitializing;
    }

    public void setInitializing(boolean isInitializing) {
        this.isInitializing = isInitializing;
    }

    public String getMd5() {
        return md5;
    }

    public String getTenant() {
        return tenant;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
        this.md5 = getMd5String(this.content);
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    /**
     * Add listener
     * if CacheData already set new content, Listener should init lastCallMd5 by CacheData.md5
     *
     * @param configListener listener
     */
    public void addListener(ConfigListener configListener) {
        if (null == configListener) {
            throw new IllegalArgumentException("listener is null");
        }
        ManagerListenerWrap wrap = (configListener instanceof AbstractConfigChangeConfigListener) ?
            new ManagerListenerWrap(configListener, md5, content) : new ManagerListenerWrap(configListener, md5);

        if (listeners.addIfAbsent(wrap)) {
            log.info("[{}] [add-listener] ok, tenant={}, dataId={}, group={}, cnt={}", name, tenant, dataId, group,
                listeners.size());
        }
    }

    public void removeListener(ConfigListener configListener) {
        if (null == configListener) {
            throw new IllegalArgumentException("listener is null");
        }
        ManagerListenerWrap wrap = new ManagerListenerWrap(configListener);
        if (listeners.remove(wrap)) {
            log.info("[{}] [remove-listener] ok, dataId={}, group={}, cnt={}", name, dataId, group, listeners.size());
        }
    }

    /**
     * 返回监听器列表上的迭代器，只读。保证不返回NULL。
     */
    public List<ConfigListener> getListeners() {
        List<ConfigListener> result = new ArrayList<ConfigListener>();
        for (ManagerListenerWrap wrap : listeners) {
            result.add(wrap.configListener);
        }
        return result;
    }

    public long getLocalConfigInfoVersion() {
        return localConfigLastModified;
    }

    public void setLocalConfigInfoVersion(long localConfigLastModified) {
        this.localConfigLastModified = localConfigLastModified;
    }

    public boolean isUseLocalConfigInfo() {
        return isUseLocalConfig;
    }

    public void setUseLocalConfigInfo(boolean useLocalConfigInfo) {
        this.isUseLocalConfig = useLocalConfigInfo;
        if (!useLocalConfigInfo) {
            localConfigLastModified = -1;
        }
    }

    public int getTaskId() {
        return taskId;
    }

    public void setTaskId(int taskId) {
        this.taskId = taskId;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((dataId == null) ? 0 : dataId.hashCode());
        result = prime * result + ((group == null) ? 0 : group.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (null == obj || obj.getClass() != getClass()) {
            return false;
        }
        if (this == obj) {
            return true;
        }
        CacheData other = (CacheData) obj;
        return dataId.equals(other.dataId) && group.equals(other.group);
    }

    @Override
    public String toString() {
        return "CacheData [" + dataId + ", " + group + "]";
    }

    /**
     * 比较缓存与缓存监听器md5值，不一致则通知监听器
     */
    public void checkListenerMd5() {
        for (ManagerListenerWrap managerListenerWrap : listeners) {
            if (!md5.equals(managerListenerWrap.lastCallMd5)) {
                safeNotifyListener(dataId, group, content, type, md5, managerListenerWrap);
            }
        }
    }

    /**
     * 通知监听器更新配置，本质发送RefreshEvent事件通知Spring IOC刷新配置
     * @param dataId 数据ID
     * @param group 组名
     * @param content 缓存配置内容
     * @param type 类型
     * @param md5 缓存MD5
     * @param listenerWrap 监听器
     */
    private void safeNotifyListener(final String dataId, final String group, final String content, final String type,
                                    final String md5, final ManagerListenerWrap listenerWrap) {
        final ConfigListener configListener = listenerWrap.configListener;

        Runnable job = () -> {
            ClassLoader myClassLoader = Thread.currentThread().getContextClassLoader();
            ClassLoader appClassLoader = configListener.getClass().getClassLoader();
            try {
                //共享配置监听器
                if (configListener instanceof AbstractSharedConfigListener) {
                    AbstractSharedConfigListener adapter = (AbstractSharedConfigListener) configListener;
                    adapter.fillContext(dataId, group);
                    log.info("[{}] [notify-context] dataId={}, group={}, md5={}", name, dataId, group, md5);
                }
                // 执行回调之前先将线程classloader设置为具体webapp的classloader，以免回调方法中调用spi接口是出现异常或错用（多应用部署才会有该问题）。
                Thread.currentThread().setContextClassLoader(appClassLoader);

                ConfigResponse configResponse = new ConfigResponse();
                configResponse.setDataId(dataId);
                configResponse.setGroup(group);
                configResponse.setContent(content);
                configFilterChainManager.doFilter(null, configResponse);
                String tempContent = configResponse.getContent();

                //回调监听器获取配置信息，发送RefreshEvent 通知Spring刷新配置，相当于重新加载配置
                configListener.receiveConfigInfo(tempContent);

                // compare lastContent and content
                if (configListener instanceof AbstractConfigChangeConfigListener) {
                    Map data = ConfigChangeHandler.getInstance().parseChangeData(listenerWrap.lastContent, content, type);
                    ConfigChangeEvent event = new ConfigChangeEvent(data);
                    ((AbstractConfigChangeConfigListener) configListener).receiveConfigChange(event);
                    listenerWrap.lastContent = content;
                }

                // 更新配置监听器MD5值
                listenerWrap.lastCallMd5 = md5;
                log.info("[{}] [notify-ok] dataId={}, group={}, md5={}, listener={} ", name, dataId, group, md5, configListener);
            } catch (NacosException nacosException) {
                log.error("[{}] [notify-error] dataId={}, group={}, md5={}, listener={} errCode={} errMsg={}", name,
                    dataId, group, md5, configListener, nacosException.getErrCode(), nacosException.getErrMsg());
            } catch (Throwable t) {
                log.error("[{}] [notify-error] dataId={}, group={}, md5={}, listener={} tx={}", name, dataId, group, md5, configListener, t.getCause());
            } finally {
                Thread.currentThread().setContextClassLoader(myClassLoader);
            }
        };

        final long startNotify = System.currentTimeMillis();
        try {
            //若监听器维护线程池则交给线程池运行，否则同步运行
            if (null != configListener.getExecutor()) {
                configListener.getExecutor().execute(job);
            } else {
                job.run();
            }
        } catch (Throwable t) {
            log.error("[{}] [notify-error] dataId={}, group={}, md5={}, listener={} throwable={}", name, dataId, group,
                md5, configListener, t.getCause());
        }
        final long finishNotify = System.currentTimeMillis();
        log.info("[{}] [notify-listener] time cost={}ms in ClientWorker, dataId={}, group={}, md5={}, listener={} ",
            name, (finishNotify - startNotify), dataId, group, md5, configListener);
    }

    static public String getMd5String(String config) {
        return (null == config) ? Constants.NULL : MD5.getInstance().getMD5String(config);
    }

    /**
     * 从本地磁盘加载缓存配置
     * @param name
     * @param dataId
     * @param group
     * @param tenant
     * @return
     */
    private String loadCacheContentFromDiskLocal(String name, String dataId, String group, String tenant) {
        String content = LocalConfigInfoProcessor.getFailover(name, dataId, group, tenant);
        content = (null != content) ? content : LocalConfigInfoProcessor.getSnapshot(name, dataId, group, tenant);
        return content;
    }

}