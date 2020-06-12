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

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.common.Constants;
import com.alibaba.nacos.api.config.ConfigType;
import com.alibaba.nacos.api.config.listener.ConfigListener;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.client.config.common.GroupKey;
import com.alibaba.nacos.client.config.filter.impl.ConfigFilterChainManager;
import com.alibaba.nacos.client.config.http.HttpAgent;
import com.alibaba.nacos.client.config.impl.HttpSimpleClient.HttpResult;
import com.alibaba.nacos.client.config.utils.ContentUtils;
import com.alibaba.nacos.client.config.utils.MD5;
import com.alibaba.nacos.client.monitor.MetricsMonitor;
import com.alibaba.nacos.client.naming.utils.CollectionUtils;
import com.alibaba.nacos.client.utils.ParamUtil;
import com.alibaba.nacos.client.utils.TenantUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URLDecoder;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.alibaba.nacos.api.common.Constants.*;

/**
 * Longpolling
 *
 * @author Nacos
 */
@Slf4j
public class ClientWorker {

    @SuppressWarnings("PMD.ThreadPoolCreationRule")
    public ClientWorker(final HttpAgent httpAgent, final ConfigFilterChainManager configFilterChainManager, final Properties properties) {
        this.httpAgent = httpAgent;
        this.configFilterChainManager = configFilterChainManager;

        // Initialize the timeout parameter
        timeout = Math.max(NumberUtils.toInt(properties.getProperty(PropertyKeyConst.CONFIG_LONG_POLL_TIMEOUT),
                Constants.CONFIG_LONG_POLL_TIMEOUT), Constants.MIN_CONFIG_LONG_POLL_TIMEOUT);
        taskPenaltyTime = NumberUtils.toInt(properties.getProperty(PropertyKeyConst.CONFIG_RETRY_TIME), Constants.CONFIG_RETRY_TIME);
        enableRemoteSyncConfig = Boolean.parseBoolean(properties.getProperty(PropertyKeyConst.ENABLE_REMOTE_SYNC_CONFIG));

        executor = Executors.newScheduledThreadPool(1, runnable -> {
            Thread t = new Thread(runnable);
            t.setName("com.alibaba.nacos.client.Worker." + httpAgent.getName());
            t.setDaemon(true);
            return t;
        });

        executorService = Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors(), r -> {
            Thread t = new Thread(r);
            t.setName("com.alibaba.nacos.client.Worker.longPolling." + httpAgent.getName());
            t.setDaemon(true);
            return t;
        });

        // 长轮询检查nacos服务端配置更新
        executor.scheduleWithFixedDelay(() -> {
            try {
                checkConfigInfo();
            } catch (Throwable e) {
                log.error("[" + httpAgent.getName() + "] [sub-check] rotate check error", e);
            }
        }, 1L, 10L, TimeUnit.MILLISECONDS);
    }

    public void addConfigListeners(String dataId, String group, List<? extends ConfigListener> listeners) {
        group = null2defaultGroup(group);
        CacheData cache = addCacheDataIfAbsent(dataId, group);
        for (ConfigListener listener : listeners) {
            cache.addListener(listener);
        }
    }

    public void removeConfigListener(String dataId, String group, ConfigListener listener) {
        group = null2defaultGroup(group);
        CacheData cache = getCache(dataId, group);
        if (null != cache) {
            cache.removeListener(listener);
            if (cache.getListeners().isEmpty()) {
                removeCache(dataId, group);
            }
        }
    }

    public void addTenantListeners(String dataId, String group, List<? extends ConfigListener> listeners) throws NacosException {
        group = null2defaultGroup(group);
        String tenant = httpAgent.getTenant();
        CacheData cache = addCacheDataIfAbsent(dataId, group, tenant);
        for (ConfigListener listener : listeners) {
            cache.addListener(listener);
        }
    }

    public void addTenantListenersWithContent(String dataId, String group, String content, List<? extends ConfigListener> listeners) throws NacosException {
        group = null2defaultGroup(group);
        String tenant = httpAgent.getTenant();
        CacheData cache = addCacheDataIfAbsent(dataId, group, tenant);
        cache.setContent(content);
        for (ConfigListener listener : listeners) {
            cache.addListener(listener);
        }
    }

    public void removeTenantListener(String dataId, String group, ConfigListener listener) {
        group = null2defaultGroup(group);
        String tenant = httpAgent.getTenant();
        CacheData cache = getCache(dataId, group, tenant);
        if (null != cache) {
            cache.removeListener(listener);
            if (cache.getListeners().isEmpty()) {
                removeCache(dataId, group, tenant);
            }
        }
    }

    void removeCache(String dataId, String group) {
        String groupKey = GroupKey.getKey(dataId, group);
        synchronized (cacheMap) {
            Map<String, CacheData> copy = new HashMap<String, CacheData>(cacheMap.get());
            copy.remove(groupKey);
            cacheMap.set(copy);
        }
        log.info("[{}] [unsubscribe] {}", httpAgent.getName(), groupKey);

        MetricsMonitor.getListenConfigCountMonitor().set(cacheMap.get().size());
    }

    public void removeCache(String dataId, String group, String tenant) {
        String groupKey = GroupKey.getKeyTenant(dataId, group, tenant);
        synchronized (cacheMap) {
            Map<String, CacheData> copy = new HashMap<>(cacheMap.get());
            copy.remove(groupKey);
            cacheMap.set(copy);
        }
        log.info("[{}] [unsubscribe] {}", httpAgent.getName(), groupKey);

        MetricsMonitor.getListenConfigCountMonitor().set(cacheMap.get().size());
    }

    public CacheData addCacheDataIfAbsent(String dataId, String group) {
        CacheData cache = getCache(dataId, group);
        if (null != cache) {
            return cache;
        }

        String key = GroupKey.getKey(dataId, group);
        cache = new CacheData(configFilterChainManager, httpAgent.getName(), dataId, group);

        synchronized (cacheMap) {
            CacheData cacheFromMap = getCache(dataId, group);
            // multiple listeners on the same dataid+group and race condition,so double check again
            //other listener thread beat me to set to cacheMap
            if (null != cacheFromMap) {
                cache = cacheFromMap;
                //reset so that server not hang this check
                cache.setInitializing(true);
            } else {
                int taskId = cacheMap.get().size() / (int) ParamUtil.getPerTaskConfigSize();
                cache.setTaskId(taskId);
            }

            Map<String, CacheData> copy = new HashMap<String, CacheData>(cacheMap.get());
            copy.put(key, cache);
            cacheMap.set(copy);
        }

        log.info("[{}] [subscribe] {}", httpAgent.getName(), key);

        MetricsMonitor.getListenConfigCountMonitor().set(cacheMap.get().size());

        return cache;
    }

    public CacheData addCacheDataIfAbsent(String dataId, String group, String tenant) throws NacosException {
        CacheData cache = getCache(dataId, group, tenant);
        if (null != cache) {
            return cache;
        }
        String key = GroupKey.getKeyTenant(dataId, group, tenant);
        synchronized (cacheMap) {
            CacheData cacheFromMap = getCache(dataId, group, tenant);
            // multiple listeners on the same dataid+group and race condition,so
            // double check again
            // other listener thread beat me to set to cacheMap
            if (null != cacheFromMap) {
                cache = cacheFromMap;
                // reset so that server not hang this check
                cache.setInitializing(true);
            } else {
                cache = new CacheData(configFilterChainManager, httpAgent.getName(), dataId, group, tenant);
                // fix issue # 1317
                if (enableRemoteSyncConfig) {
                    String[] ct = getServerConfig(dataId, group, tenant, 6000L);
                    cache.setContent(ct[0]);
                }
            }

            Map<String, CacheData> copy = new HashMap<String, CacheData>(cacheMap.get());
            copy.put(key, cache);
            cacheMap.set(copy);
        }
        log.info("[{}] [subscribe] {}", httpAgent.getName(), key);

        MetricsMonitor.getListenConfigCountMonitor().set(cacheMap.get().size());

        return cache;
    }

    public CacheData getCache(String dataId, String group) {
        return getCache(dataId, group, TenantUtil.getUserTenantForAcm());
    }

    public CacheData getCache(String dataId, String group, String tenant) {
        if (null == dataId || null == group) {
            throw new IllegalArgumentException();
        }
        return cacheMap.get().get(GroupKey.getKeyTenant(dataId, group, tenant));
    }

    public String[] getServerConfig(String dataId, String group, String tenant, long readTimeout)
        throws NacosException {
        String[] ct = new String[2];
        if (StringUtils.isBlank(group)) {
            group = Constants.DEFAULT_GROUP;
        }

        HttpResult result = null;
        try {
            List<String> params = null;
            if (StringUtils.isBlank(tenant)) {
                params = new ArrayList<String>(Arrays.asList("dataId", dataId, "group", group));
            } else {
                params = new ArrayList<String>(Arrays.asList("dataId", dataId, "group", group, "tenant", tenant));
            }
            result = httpAgent.httpGet(Constants.CONFIG_CONTROLLER_PATH, null, params, httpAgent.getEncode(), readTimeout);
        } catch (IOException e) {
            String message = String.format(
                "[%s] [sub-server] get server config exception, dataId=%s, group=%s, tenant=%s", httpAgent.getName(),
                dataId, group, tenant);
            log.error(message, e);
            throw new NacosException(NacosException.SERVER_ERROR, e);
        }

        switch (result.code) {
            case HttpURLConnection.HTTP_OK:
                LocalConfigInfoProcessor.saveSnapshot(httpAgent.getName(), dataId, group, tenant, result.content);
                ct[0] = result.content;
                if (result.headers.containsKey(CONFIG_TYPE)) {
                    ct[1] = result.headers.get(CONFIG_TYPE).get(0);
                } else {
                    ct[1] = ConfigType.TEXT.getType();
                }
                return ct;
            case HttpURLConnection.HTTP_NOT_FOUND:
                LocalConfigInfoProcessor.saveSnapshot(httpAgent.getName(), dataId, group, tenant, null);
                return ct;
            case HttpURLConnection.HTTP_CONFLICT: {
                log.error(
                    "[{}] [sub-server-error] get server config being modified concurrently, dataId={}, group={}, " + "tenant={}", httpAgent.getName(), dataId, group, tenant);
                throw new NacosException(NacosException.CONFLICT,
                    "data being modified, dataId=" + dataId + ",group=" + group + ",tenant=" + tenant);
            }
            case HttpURLConnection.HTTP_FORBIDDEN: {
                log.error("[{}] [sub-server-error] no right, dataId={}, group={}, tenant={}", httpAgent.getName(), dataId, group, tenant);
                throw new NacosException(result.code, result.content);
            }
            default: {
                log.error("[{}] [sub-server-error]  dataId={}, group={}, tenant={}, code={}", httpAgent.getName(), dataId, group, tenant, result.code);
                throw new NacosException(result.code, "http error, code=" + result.code + ",dataId=" + dataId + ",group=" + group + ",tenant=" + tenant);
            }
        }
    }

    /** 检查NACOS本地配置缓存： 同步本地缓存文件数据到JVM缓存，MD5值不同步 **/
    private void syncDiskCacheToJvmCacheConfig(CacheData localCacheData) {
        final String dataId = localCacheData.dataId;
        final String group = localCacheData.group;
        final String tenant = localCacheData.tenant;
        File path = LocalConfigInfoProcessor.getFailoverFile(httpAgent.getName(), dataId, group, tenant);

        // 没有 -> 有
        // 场景：应用再次启动，CacheData默认从nacos加载未使用本地缓存文件数据,同步本地文件缓存数据到CacheData
        if (!localCacheData.isUseLocalConfigInfo() && path.exists()) {
            String content = LocalConfigInfoProcessor.getFailover(httpAgent.getName(), dataId, group, tenant);
            localCacheData.setUseLocalConfigInfo(true);
            localCacheData.setLocalConfigInfoVersion(path.lastModified());
            localCacheData.setContent(content);
            String md5 = MD5.getInstance().getMD5String(content);
            log.warn("[{}] [failover-change] failover file created. dataId={}, group={}, tenant={}, md5={}, content={}",
                    httpAgent.getName(), dataId, group, tenant, md5, ContentUtils.truncateContent(content));
            return;
        }

        // 有 -> 没有
        //场景： 应用正常配置初始化后，本地缓存配置目录被删除，重置本地缓存状态，标识本地缓存只使用nacos服务端配置缓存
        if (localCacheData.isUseLocalConfigInfo() && !path.exists()) {
            localCacheData.setUseLocalConfigInfo(false);
            log.warn("[{}] [failover-change] failover file deleted. dataId={}, group={}, tenant={}", httpAgent.getName(),
                dataId, group, tenant);
            return;
        }

        // 有变更： JVM配置缓存和本地缓存配置数据版本不一致，同步本地文件缓存数据到JVM缓存
        if (localCacheData.isUseLocalConfigInfo() && path.exists()
            && localCacheData.getLocalConfigInfoVersion() != path.lastModified()) {

            String content = LocalConfigInfoProcessor.getFailover(httpAgent.getName(), dataId, group, tenant);
            String md5 = MD5.getInstance().getMD5String(content);
            localCacheData.setUseLocalConfigInfo(true);
            localCacheData.setLocalConfigInfoVersion(path.lastModified());
            localCacheData.setContent(content);
            log.warn("[{}] [failover-change] failover file changed. dataId={}, group={}, tenant={}, md5={}, content={}",
                httpAgent.getName(), dataId, group, tenant, md5, ContentUtils.truncateContent(content));
        }
    }

    private String null2defaultGroup(String group) {
        return (null == group) ? Constants.DEFAULT_GROUP : group.trim();
    }

    public void checkConfigInfo() {
        // 分任务
        int listenerSize = cacheMap.get().size();
        // 向上取整为批数
        int longingTaskCount = (int) Math.ceil(listenerSize / ParamUtil.getPerTaskConfigSize());
        if (longingTaskCount > currentLongingTaskCount) {
            //一个配置1个长轮询任务监听
            for (int i = (int) currentLongingTaskCount; i < longingTaskCount; i++) {
                executorService.execute(new LongPollingRunnable(i));
            }
            currentLongingTaskCount = longingTaskCount;
        }
    }

    /**
     * 获取配置已变更的GroupKey
     */
    List<String> checkUpdateDataIds(List<CacheData> localCacheDatas, List<String> inInitializingCacheList) throws IOException {
        StringBuilder cacheDataStr = new StringBuilder();
        for (CacheData cacheData : localCacheDatas) {
            if (!cacheData.isUseLocalConfigInfo()) { //缓存配置不使用本地文件配置缓存,及JVM配置缓存与本地文件缓存不一致
                cacheDataStr.append(cacheData.dataId).append(WORD_SEPARATOR);
                cacheDataStr.append(cacheData.group).append(WORD_SEPARATOR);
                if (StringUtils.isBlank(cacheData.tenant)) {
                    cacheDataStr.append(cacheData.getMd5()).append(LINE_SEPARATOR);
                } else {
                    cacheDataStr.append(cacheData.getMd5()).append(WORD_SEPARATOR);
                    cacheDataStr.append(cacheData.getTenant()).append(LINE_SEPARATOR);
                }

                // 获取已初始化的缓存GroupKey列表
                if (cacheData.isInitializing()) {
                    inInitializingCacheList.add(GroupKey.getKeyTenant(cacheData.dataId, cacheData.group, cacheData.tenant));
                }
            }
        }

        //不存在不一致配置缓存直接返回，不进一步进行缓存校验
        if (StringUtils.isBlank(cacheDataStr.toString())) {
            return Collections.emptyList();
        }

        // 本地配置缓存与NACOS配置数据比较
        List<String> requestParams = new ArrayList<>(2);
        requestParams.add(Constants.PROBE_MODIFY_REQUEST);
        requestParams.add(cacheDataStr.toString());

        List<String> headers = new ArrayList<>(2);
        headers.add("Long-Pulling-Timeout"); //设置长轮询超时时间
        headers.add("" + timeout);

        // told server do not hang me up if new initializing cacheData added in 通知服务器不要挂起长轮询请求
        // 说明：本地和JVM缓存不一致需要即时更新，服务端需立即详情请求，如果本地缓存一致，长轮询请求挂起正常类似心跳检测，防止无效请求过多造成nacos服务请求
        if (!CollectionUtils.isEmpty(inInitializingCacheList)) {
            headers.add("Long-Pulling-Timeout-No-Hangup");
            headers.add("true");
        }

        try {
            // In order to prevent the server from handling the delay of the client's long task,
            // increase the client's read timeout to avoid this problem.
            long readTimeoutMs = timeout + (long) Math.round(timeout >> 1); //读取timeout超时比长轮询时长稍长，预防网络问题导致读取超时，服务端响应稍短
            HttpResult result = httpAgent.httpPost(Constants.CONFIG_CONTROLLER_PATH + "/listener", headers, requestParams, httpAgent.getEncode(), readTimeoutMs);
            if (HttpURLConnection.HTTP_OK == result.code) {
                setHealthServer(true);
                String response = result.content;

                if (StringUtils.isBlank(response)) {
                    return Collections.emptyList();
                }

                try {
                    response = URLDecoder.decode(response, "UTF-8");
                } catch (Exception e) {
                    log.error("[" + httpAgent.getName() + "] [polling-resp] decode modifiedDataIdsString error", e);
                }

                List<String> updateList = new LinkedList<String>();
                for (String dataIdAndGroup : response.split(LINE_SEPARATOR)) {
                    if (!StringUtils.isBlank(dataIdAndGroup)) {
                        String[] keyArr = dataIdAndGroup.split(WORD_SEPARATOR);
                        String dataId = keyArr[0];
                        String group = keyArr[1];
                        if (keyArr.length == 2) {
                            updateList.add(GroupKey.getKey(dataId, group));
                            log.info("[{}] [polling-resp] config changed. dataId={}, group={}", httpAgent.getName(), dataId, group);
                        } else if (keyArr.length == 3) {
                            String tenant = keyArr[2];
                            updateList.add(GroupKey.getKeyTenant(dataId, group, tenant));
                            log.info("[{}] [polling-resp] config changed. dataId={}, group={}, tenant={}", httpAgent.getName(), dataId, group, tenant);
                        } else {
                            log.error("[{}] [polling-resp] invalid dataIdAndGroup error {}", httpAgent.getName(), dataIdAndGroup);
                        }
                    }
                }
                return updateList;
            } else {
                setHealthServer(false);
                log.error("[{}] [check-update] get changed dataId error, code: {}", httpAgent.getName(), result.code);
            }
        } catch (IOException e) {
            setHealthServer(false);
            log.error("[" + httpAgent.getName() + "] [check-update] get changed dataId exception", e);
            throw e;
        }
        return Collections.emptyList();
    }


    /**
     * 长轮询检查nacos配置数据
     * **/
    private class LongPollingRunnable implements Runnable {

        private final int taskId;

        public LongPollingRunnable(int taskId) {
            this.taskId = taskId;
        }

        @Override
        public void run() {

            List<CacheData> localCacheDataList = new ArrayList<>();
            List<String> inInitializingCacheList = new ArrayList<>();
            try {
                // check failover config： 检查JVM和本地配置文件缓存
                for (CacheData localCacheData : cacheMap.get().values()) {
                    if (localCacheData.getTaskId() == taskId) {
                        localCacheDataList.add(localCacheData);
                        try {
                            syncDiskCacheToJvmCacheConfig(localCacheData);

                            //使用本地缓存配置文件数据检查MD5，同步缓存MD5到监听器
                            if (localCacheData.isUseLocalConfigInfo()) {
                                localCacheData.checkListenerMd5();
                            }
                        } catch (Exception e) {
                            log.error("get local config info error", e);
                        }
                    }
                }

                // check server config： 比较本地缓存MD5与服务器缓存，再次确认已经变更的缓存坐标
                List<String> changedGroupKeys = checkUpdateDataIds(localCacheDataList, inInitializingCacheList);
                log.info("get changedGroupKeys:" + changedGroupKeys);

                //从nacos服务器获取变更的配置信息
                for (String groupKey : changedGroupKeys) {
                    String[] key = GroupKey.parseKey(groupKey);
                    String dataId = key[0];
                    String group = key[1];
                    String tenant = null;
                    if (key.length == 3) {
                        tenant = key[2];
                    }
                    try {
                        String[] ct = getServerConfig(dataId, group, tenant, 6000L);

                        //更新本地缓存
                        CacheData localCacheData = cacheMap.get().get(GroupKey.getKeyTenant(dataId, group, tenant));
                        localCacheData.setContent(ct[0]);
                        if (null != ct[1]) {
                            localCacheData.setType(ct[1]);
                        }
                        log.info("[{}] [data-received] dataId={}, group={}, tenant={}, md5={}, content={}, type={}",
                            httpAgent.getName(), dataId, group, tenant, localCacheData.getMd5(),
                            ContentUtils.truncateContent(ct[0]), ct[1]);
                    } catch (NacosException ioe) {
                        String message = String.format(
                            "[%s] [get-update] get changed config exception. dataId=%s, group=%s, tenant=%s",
                            httpAgent.getName(), dataId, group, tenant);
                        log.error(message, ioe);
                    }
                }
                for (CacheData cacheData : localCacheDataList) {
                    //未初始化，或已初始化但未使用本地文件缓存配置数据
                    if (!cacheData.isInitializing() || inInitializingCacheList
                        .contains(GroupKey.getKeyTenant(cacheData.dataId, cacheData.group, cacheData.tenant))) {
                        cacheData.checkListenerMd5();
                        cacheData.setInitializing(false);
                    }
                }
                inInitializingCacheList.clear();
                executorService.execute(this);

            } catch (Throwable e) {

                // If the rotation training task is abnormal, the next execution time of the task will be punished
                log.error("longPolling error : ", e);
                executorService.schedule(this, taskPenaltyTime, TimeUnit.MILLISECONDS);
            }
        }
    }

    public boolean isHealthServer() {
        return isHealthServer;
    }

    private void setHealthServer(boolean isHealthServer) {
        this.isHealthServer = isHealthServer;
    }

    final ScheduledExecutorService executor;
    final ScheduledExecutorService executorService;

    /**
     * groupKey -> cacheData
     */
    private final AtomicReference<Map<String, CacheData>> cacheMap = new AtomicReference<Map<String, CacheData>>(
        new HashMap<String, CacheData>());

    private final HttpAgent httpAgent;
    private final ConfigFilterChainManager configFilterChainManager;
    private boolean isHealthServer = true;
    private final long timeout;

    /** 当前长轮询任务数 **/
    private double currentLongingTaskCount = 0;
    private final int taskPenaltyTime;
    private boolean enableRemoteSyncConfig = false;
}
