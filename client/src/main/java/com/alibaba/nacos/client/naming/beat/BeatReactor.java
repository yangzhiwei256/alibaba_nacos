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
package com.alibaba.nacos.client.naming.beat;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.nacos.api.common.Constants;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.CommonParams;
import com.alibaba.nacos.api.naming.NamingResponseCode;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.alibaba.nacos.api.naming.utils.NamingUtils;
import com.alibaba.nacos.client.monitor.MetricsMonitor;
import com.alibaba.nacos.client.naming.net.NamingProxy;
import com.alibaba.nacos.client.naming.utils.UtilAndComs;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;



/**
 * 服务心跳反应器
 * @author harold
 */
@Slf4j
public class BeatReactor {

    private final ScheduledExecutorService executorService;

    private final NamingProxy serverProxy;

    private boolean lightBeatEnabled = false;

    /** 缓存心跳信息 **/
    public final Map<String, BeatInfo> dom2Beat = new ConcurrentHashMap<>();

    public BeatReactor(NamingProxy serverProxy) {
        this(serverProxy, UtilAndComs.DEFAULT_CLIENT_BEAT_THREAD_COUNT);
    }

    public BeatReactor(NamingProxy serverProxy, int threadCount) {
        this.serverProxy = serverProxy;
        executorService = new ScheduledThreadPoolExecutor(threadCount, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            thread.setName("com.alibaba.nacos.naming.beat.sender");
            return thread;
        });
    }

    /**
     * 添加服务心跳信息
     * @param serviceName 服务名称
     * @param beatInfo 心跳信息
     */
    public void addBeatInfo(String serviceName, BeatInfo beatInfo) {
        log.info("[BEAT] adding heart beat: {} to beat map.", beatInfo);
        String key = buildKey(serviceName, beatInfo.getIp(), beatInfo.getPort());
        BeatInfo existBeat = null;
        //fix #1733
        if ((existBeat = dom2Beat.remove(key)) != null) {
            existBeat.setStopped(true);
        }
        dom2Beat.put(key, beatInfo);
        executorService.schedule(new BeatTask(beatInfo), beatInfo.getPeriod(), TimeUnit.MILLISECONDS);
        MetricsMonitor.getDom2BeatSizeMonitor().set(dom2Beat.size());
    }

    /**
     * 清除服务心跳信息（取消服务注册）
     * @param serviceName 服务名称
     * @param ip 客户端服务IP
     * @param port 客户端服务端口
     */
    public void removeBeatInfo(String serviceName, String ip, int port) {
        log.info("[BEAT] removing heart beat: {}:{}:{} from beat map.", serviceName, ip, port);
        BeatInfo beatInfo = dom2Beat.remove(buildKey(serviceName, ip, port));
        if (beatInfo == null) {
            return;
        }
        beatInfo.setStopped(true);
        MetricsMonitor.getDom2BeatSizeMonitor().set(dom2Beat.size());
    }

    private String buildKey(String serviceName, String ip, int port) {
        return serviceName + Constants.NAMING_INSTANCE_ID_SPLITTER + ip + Constants.NAMING_INSTANCE_ID_SPLITTER + port;
    }

    /**
     * 服务心跳检测任务
     */
    private class BeatTask implements Runnable {

        BeatInfo beatInfo;

        public BeatTask(BeatInfo beatInfo) {
            this.beatInfo = beatInfo;
        }

        @Override
        public void run() {
            if (beatInfo.isStopped()) {
                return;
            }
            long nextTime = beatInfo.getPeriod();
            try {
                JSONObject result = serverProxy.sendBeat(beatInfo, BeatReactor.this.lightBeatEnabled);
                long interval = result.getIntValue("clientBeatInterval");
                boolean lightBeatEnabled = false;
                if (result.containsKey(CommonParams.LIGHT_BEAT_ENABLED)) {
                    lightBeatEnabled = result.getBooleanValue(CommonParams.LIGHT_BEAT_ENABLED);
                }
                BeatReactor.this.lightBeatEnabled = lightBeatEnabled;
                if (interval > 0) {
                    nextTime = interval;
                }
                int code = NamingResponseCode.OK;
                if (result.containsKey(CommonParams.CODE)) {
                    code = result.getIntValue(CommonParams.CODE);
                }

                //服务未注册到nacos，则重新注册
                if (code == NamingResponseCode.RESOURCE_NOT_FOUND) {
                    Instance instance = new Instance();
                    instance.setPort(beatInfo.getPort());
                    instance.setIp(beatInfo.getIp());
                    instance.setWeight(beatInfo.getWeight());
                    instance.setMetadata(beatInfo.getMetadata());
                    instance.setClusterName(beatInfo.getCluster());
                    instance.setServiceName(beatInfo.getServiceName());
                    instance.setInstanceId(instance.getInstanceId());
                    instance.setEphemeral(true);
                    try {
                        serverProxy.registerService(beatInfo.getServiceName(),NamingUtils.getGroupName(beatInfo.getServiceName()), instance);
                    } catch (Exception ignore) {
                    }
                }
            } catch (NacosException ne) {
                log.error("[CLIENT-BEAT] failed to send beat: {}, code: {}, msg: {}",
                    JSON.toJSONString(beatInfo), ne.getErrCode(), ne.getErrMsg());

            }
            //重新调度下个心跳任务
            executorService.schedule(new BeatTask(beatInfo), nextTime, TimeUnit.MILLISECONDS);
        }
    }
}
