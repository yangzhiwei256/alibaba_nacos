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

import com.alibaba.nacos.config.server.model.SampleResult;
import com.alibaba.nacos.config.server.monitor.MetricsMonitor;
import com.alibaba.nacos.config.server.utils.GroupKey;
import com.alibaba.nacos.config.server.utils.MD5Util;
import com.alibaba.nacos.config.server.utils.RequestUtil;
import com.alibaba.nacos.config.server.utils.event.EventDispatcher.AbstractEventListener;
import com.alibaba.nacos.config.server.utils.event.EventDispatcher.Event;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.*;
import java.util.concurrent.*;

/**
 * 长轮询服务。负责处理
 *
 * @author Nacos
 */
@Service
@Slf4j
public class LongPollingService extends AbstractEventListener {

    private static final int FIXED_POLLING_INTERVAL_MS = 10000;

    private static final int SAMPLE_PERIOD = 100;

    private static final int SAMPLE_TIMES = 3;

    private static final String TRUE_STR = "true";

    private Map<String, Long> retainIps = new ConcurrentHashMap<String, Long>();

    // =================

    static public final String LONG_POLLING_HEADER = "Long-Pulling-Timeout";
    static public final String LONG_POLLING_NO_HANG_UP_HEADER = "Long-Pulling-Timeout-No-Hangup";

    final ScheduledExecutorService scheduledExecutorService;

    /**
     * 长轮询订阅关系
     */
    final Queue<ClientLongPolling> allSubs;

    @SuppressWarnings("PMD.ThreadPoolCreationRule")
    public LongPollingService() {
        allSubs = new ConcurrentLinkedQueue<>();
        scheduledExecutorService = Executors.newScheduledThreadPool(1, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            thread.setName("com.alibaba.nacos.LongPolling");
            return thread;
        });
        scheduledExecutorService.scheduleWithFixedDelay(new StatTask(), 0L, 10L, TimeUnit.SECONDS);
    }

    private static boolean isFixedPolling() {
        return SwitchService.getSwitchBoolean(SwitchService.FIXED_POLLING, false);
    }

    private static int getFixedPollingInterval() {
        return SwitchService.getSwitchInteger(SwitchService.FIXED_POLLING_INTERVAL, FIXED_POLLING_INTERVAL_MS);
    }

    public boolean isClientLongPolling(String clientIp) {
        return getClientPollingRecord(clientIp) != null;
    }

    public Map<String, String> getClientSubConfigInfo(String clientIp) {
        ClientLongPolling record = getClientPollingRecord(clientIp);
        if (record == null) {
            return Collections.emptyMap();
        }
        return record.clientMd5Map;
    }

    /**
     * 获取订阅信息
     * @param dataId 数据ID
     * @param group 组名
     * @param tenant 命名空间
     * @return
     */
    public SampleResult getSubscribeInfo(String dataId, String group, String tenant) {
        String groupKey = GroupKey.getKeyTenant(dataId, group, tenant);
        SampleResult sampleResult = new SampleResult();
        Map<String, String> listenersGroupkeyStatus = new HashMap<>(50);
        for (ClientLongPolling clientLongPolling : allSubs) {
            if (clientLongPolling.clientMd5Map.containsKey(groupKey)) {
                listenersGroupkeyStatus.put(clientLongPolling.ip, clientLongPolling.clientMd5Map.get(groupKey));
            }
        }
        sampleResult.setListenersGroupKeyStatus(listenersGroupkeyStatus);
        return sampleResult;
    }

    /**
     * 获取客户端IP对应订阅信息
     * @param clientIp nacos客户端ip
     * @return
     */
    public SampleResult getSubscribeInfoByIp(String clientIp) {
        SampleResult sampleResult = new SampleResult();
        Map<String, String> lisentersGroupKeyStatus = new HashMap<>(50);

        for (ClientLongPolling clientLongPolling : allSubs) {
            if (clientLongPolling.ip.equals(clientIp)) {
                // 一个ip可能有多个监听
                if (!lisentersGroupKeyStatus.equals(clientLongPolling.clientMd5Map)) {
                    lisentersGroupKeyStatus.putAll(clientLongPolling.clientMd5Map);
                }
            }
        }
        sampleResult.setListenersGroupKeyStatus(lisentersGroupKeyStatus);
        return sampleResult;
    }

    /**
     * 聚合采样结果中的采样ip和监听配置的信息；合并策略用后面的覆盖前面的是没有问题的
     * @param sampleResults sample Results
     * @return Results
     */
    public SampleResult mergeSampleResult(List<SampleResult> sampleResults) {
        SampleResult mergeResult = new SampleResult();
        Map<String, String> lisentersGroupkeyStatus = new HashMap<>(50);
        for (SampleResult sampleResult : sampleResults) {
            Map<String, String> lisentersGroupkeyStatusTmp = sampleResult.getListenersGroupkeyStatus();
            for (Map.Entry<String, String> entry : lisentersGroupkeyStatusTmp.entrySet()) {
                lisentersGroupkeyStatus.put(entry.getKey(), entry.getValue());
            }
        }
        mergeResult.setListenersGroupKeyStatus(lisentersGroupkeyStatus);
        return mergeResult;
    }

    public Map<String, Set<String>> collectApplicationSubscribeConfigInfos() {
        if (allSubs == null || allSubs.isEmpty()) {
            return null;
        }
        HashMap<String, Set<String>> app2Groupkeys = new HashMap<String, Set<String>>(50);
        for (ClientLongPolling clientLongPolling : allSubs) {
            if (StringUtils.isEmpty(clientLongPolling.appName) || "unknown".equalsIgnoreCase(
                clientLongPolling.appName)) {
                continue;
            }
            Set<String> appSubscribeConfigs = app2Groupkeys.get(clientLongPolling.appName);
            Set<String> clientSubscribeConfigs = clientLongPolling.clientMd5Map.keySet();
            if (appSubscribeConfigs == null) {
                appSubscribeConfigs = new HashSet<String>(clientSubscribeConfigs.size());
            }
            appSubscribeConfigs.addAll(clientSubscribeConfigs);
            app2Groupkeys.put(clientLongPolling.appName, appSubscribeConfigs);
        }

        return app2Groupkeys;
    }

    public SampleResult getCollectSubscribeInfo(String dataId, String group, String tenant) {
        List<SampleResult> sampleResultLst = new ArrayList<>(50);
        for (int i = 0; i < SAMPLE_TIMES; i++) {
            SampleResult sampleTmp = getSubscribeInfo(dataId, group, tenant);
            if (sampleTmp != null) {
                sampleResultLst.add(sampleTmp);
            }
            if (i < SAMPLE_TIMES - 1) {
                try {
                    Thread.sleep(SAMPLE_PERIOD);
                } catch (InterruptedException e) {
                    log.error("sleep wrong", e);
                }
            }
        }
        return mergeSampleResult(sampleResultLst);
    }

    public SampleResult getCollectSubscribeInfoByIp(String ip) {
        SampleResult sampleResult = new SampleResult();
        sampleResult.setListenersGroupKeyStatus(new HashMap<String, String>(50));
        for (int i = 0; i < SAMPLE_TIMES; i++) {
            SampleResult sampleTmp = getSubscribeInfoByIp(ip);
            if (sampleTmp != null) {
                if (sampleTmp.getListenersGroupkeyStatus() != null
                    && !sampleResult.getListenersGroupkeyStatus().equals(sampleTmp.getListenersGroupkeyStatus())) {
                    sampleResult.getListenersGroupkeyStatus().putAll(sampleTmp.getListenersGroupkeyStatus());
                }
            }
            if (i < SAMPLE_TIMES - 1) {
                try {
                    Thread.sleep(SAMPLE_PERIOD);
                } catch (InterruptedException e) {
                    log.error("sleep wrong", e);
                }
            }
        }
        return sampleResult;
    }

    private ClientLongPolling getClientPollingRecord(String clientIp) {
        if (allSubs == null) {
            return null;
        }

        for (ClientLongPolling clientLongPolling : allSubs) {
            HttpServletRequest request = (HttpServletRequest) clientLongPolling.asyncContext.getRequest();

            if (clientIp.equals(RequestUtil.getRemoteIp(request))) {
                return clientLongPolling;
            }
        }

        return null;
    }

    /**
     * 田间长轮询客户端
     * @param req
     * @param rsp
     * @param clientMd5Map 客户端MD5
     * @param probeRequestSize 客户端请求配置数据
     */
    public void addLongPollingClient(HttpServletRequest req, HttpServletResponse rsp, Map<String, String> clientMd5Map, int probeRequestSize) {

        //长轮询时长间隔
        String longPollingTimeout = req.getHeader(LongPollingService.LONG_POLLING_HEADER);
        String noHangUpFlag = req.getHeader(LongPollingService.LONG_POLLING_NO_HANG_UP_HEADER);
        String appName = req.getHeader(RequestUtil.CLIENT_APPNAME_HEADER);
        String tag = req.getHeader("Vipserver-Tag");
        int delayTime = SwitchService.getSwitchInteger(SwitchService.FIXED_DELAY_TIME, 500);
        /**
         * 提前500ms返回响应，为避免客户端超时 @qiaoyi.dingqy 2013.10.22改动  add delay time for LoadBalance
         */
        long timeout = Math.max(10000, Long.parseLong(longPollingTimeout) - delayTime);
        if (isFixedPolling()) {
            timeout = Math.max(10000, getFixedPollingInterval());
            // do nothing but set fix polling timeout
        } else {
            long start = System.currentTimeMillis();
            List<String> changedGroups = MD5Util.compareMd5(req, rsp, clientMd5Map);
            if (changedGroups.size() > 0) { //存在配置变更
                //响应客户端变更配置数据
                try {
                    String respString = MD5Util.compareMd5ResultString(changedGroups);
                    rsp.setHeader("Pragma", "no-cache");
                    rsp.setDateHeader("Expires", 0);
                    rsp.setHeader("Cache-Control", "no-cache,no-store");
                    rsp.setStatus(HttpServletResponse.SC_OK);
                    rsp.getWriter().println(respString);
                } catch (Exception se) {
                    log.error(se.toString(), se);
                }

                log.info("{}|{}|{}|{}|{}|{}|{}",
                    System.currentTimeMillis() - start, "instant", RequestUtil.getRemoteIp(req), "polling",
                    clientMd5Map.size(), probeRequestSize, changedGroups.size());
                return;
            } else if (noHangUpFlag != null && noHangUpFlag.equalsIgnoreCase(TRUE_STR)) {
                log.info("{}|{}|{}|{}|{}|{}|{}", System.currentTimeMillis() - start, "nohangup",
                    RequestUtil.getRemoteIp(req), "polling", clientMd5Map.size(), probeRequestSize,
                    changedGroups.size());
                return;
            }
        }
        String ip = RequestUtil.getRemoteIp(req);
        // 一定要由HTTP线程调用，否则离开后容器会立即发送响应
        final AsyncContext asyncContext = req.startAsync();
        // AsyncContext.setTimeout()的超时时间不准，所以只能自己控制
        asyncContext.setTimeout(0L);
        scheduledExecutorService.execute(new ClientLongPolling(asyncContext, clientMd5Map, ip, probeRequestSize, timeout, appName, tag));
    }

    @Override
    public List<Class<? extends Event>> interest() {
        List<Class<? extends Event>> eventTypes = new ArrayList<Class<? extends Event>>();
        eventTypes.add(LocalDataChangeEvent.class);
        return eventTypes;
    }

    @Override
    public void onEvent(Event event) {
        if (isFixedPolling()) {
            // ignore
        } else {
            if (event instanceof LocalDataChangeEvent) {
                LocalDataChangeEvent evt = (LocalDataChangeEvent)event;
                scheduledExecutorService.execute(new DataChangeTask(evt.groupKey, evt.isBeta, evt.betaIps));
            }
        }
    }

    static public boolean isSupportLongPolling(HttpServletRequest req) {
        return null != req.getHeader(LONG_POLLING_HEADER);
    }


    public Map<String, Long> getRetainIps() {
        return retainIps;
    }

    public void setRetainIps(Map<String, Long> retainIps) {
        this.retainIps = retainIps;
    }

    // ================= 配置数据变更任务
    private class DataChangeTask implements Runnable {

        final String groupKey;
        final long changeTime = System.currentTimeMillis();
        final boolean isBeta;
        final List<String> betaIps;
        final String tag;

        @Override
        public void run() {
            try {
                ConfigService.getContentBetaMd5(groupKey);
                for (Iterator<ClientLongPolling> iter = allSubs.iterator(); iter.hasNext(); ) {
                    ClientLongPolling clientSub = iter.next();
                    if (clientSub.clientMd5Map.containsKey(groupKey)) {
                        // 如果beta发布且不在beta列表直接跳过
                        if (isBeta && !betaIps.contains(clientSub.ip)) {
                            continue;
                        }

                        // 如果tag发布且不在tag列表直接跳过
                        if (StringUtils.isNotBlank(tag) && !tag.equals(clientSub.tag)) {
                            continue;
                        }

                        getRetainIps().put(clientSub.ip, System.currentTimeMillis());
                        iter.remove(); // 删除订阅关系
                        log.info("{}|{}|{}|{}|{}|{}|{}",
                                (System.currentTimeMillis() - changeTime),
                                "in-advance",
                                RequestUtil.getRemoteIp((HttpServletRequest)clientSub.asyncContext.getRequest()),
                                "polling",
                                clientSub.clientMd5Map.size(), clientSub.probeRequestSize, groupKey);
                        clientSub.sendResponse(Collections.singletonList(groupKey));
                    }
                }
            } catch (Throwable t) {
                log.error("data change error:" + t.getMessage(), t.getCause());
            }
        }

        DataChangeTask(String groupKey) {
            this(groupKey, false, null);
        }

        DataChangeTask(String groupKey, boolean isBeta, List<String> betaIps) {
            this(groupKey, isBeta, betaIps, null);
        }

        DataChangeTask(String groupKey, boolean isBeta, List<String> betaIps, String tag) {
            this.groupKey = groupKey;
            this.isBeta = isBeta;
            this.betaIps = betaIps;
            this.tag = tag;
        }


    }

    // =================
    class StatTask implements Runnable {
        @Override
        public void run() {
            log.info("[long-pulling] client count " + allSubs.size());
            MetricsMonitor.getLongPollingMonitor().set(allSubs.size());
        }
    }

    // ================= 客户端长轮询线程
    private class ClientLongPolling implements Runnable {

        // =================

        final AsyncContext asyncContext;
        final Map<String, String> clientMd5Map;
        final long createTime;
        final String ip;
        final String appName;
        final String tag;
        final int probeRequestSize;
        final long timeoutTime;
        Future<?> asyncTimeoutFuture;

        ClientLongPolling(AsyncContext ac, Map<String, String> clientMd5Map, String ip, int probeRequestSize,
                          long timeoutTime, String appName, String tag) {
            this.asyncContext = ac;
            this.clientMd5Map = clientMd5Map;
            this.probeRequestSize = probeRequestSize;
            this.createTime = System.currentTimeMillis();
            this.ip = ip;
            this.timeoutTime = timeoutTime;
            this.appName = appName;
            this.tag = tag;
        }

        @Override
        public void run() {
            asyncTimeoutFuture = scheduledExecutorService.schedule(() -> {
                try {
                    getRetainIps().put(ClientLongPolling.this.ip, System.currentTimeMillis());
                    /**
                     * 删除订阅关系
                     */
                    allSubs.remove(ClientLongPolling.this);

                    if (isFixedPolling()) {
                        log.info("{}|{}|{}|{}|{}|{}",
                                (System.currentTimeMillis() - createTime),
                                "fix", RequestUtil.getRemoteIp((HttpServletRequest)asyncContext.getRequest()),
                                "polling",
                                clientMd5Map.size(), probeRequestSize);
                        List<String> changedGroups = MD5Util.compareMd5(
                                (HttpServletRequest)asyncContext.getRequest(),
                                (HttpServletResponse)asyncContext.getResponse(), clientMd5Map);
                        if (changedGroups.size() > 0) {
                            sendResponse(changedGroups);
                        } else {
                            sendResponse(null);
                        }
                    } else {
                        log.info("{}|{}|{}|{}|{}|{}",
                                (System.currentTimeMillis() - createTime),
                                "timeout", RequestUtil.getRemoteIp((HttpServletRequest)asyncContext.getRequest()),
                                "polling",
                                clientMd5Map.size(), probeRequestSize);
                        sendResponse(null);
                    }
                } catch (Throwable t) {
                    log.error("long polling error:" + t.getMessage(), t.getCause());
                }

            }, timeoutTime, TimeUnit.MILLISECONDS);
            allSubs.add(this);
        }

        /**
         * 客户端响应
         * @param changedGroups 改变的缓存group
         */
        private void sendResponse(List<String> changedGroups) {
            /**
             *  取消超时任务: 立即响应长轮询请求，而不是交由定时任务定时调度
             */
            if (null != asyncTimeoutFuture) {
                asyncTimeoutFuture.cancel(false);
            }

            /**
             * 通知容器发送HTTP响应
             */
            if (null == changedGroups) {
                asyncContext.complete();
                return;
            }

            HttpServletResponse response = (HttpServletResponse)asyncContext.getResponse();
            try {
                String respString = MD5Util.compareMd5ResultString(changedGroups);
                response.setHeader("Pragma", "no-cache");
                response.setDateHeader("Expires", 0);
                response.setHeader("Cache-Control", "no-cache,no-store");
                response.setStatus(HttpServletResponse.SC_OK);
                response.getWriter().println(respString);
                asyncContext.complete();
            } catch (Exception se) {
                log.error(se.toString(), se);
                asyncContext.complete();
            }
        }
    }

}
