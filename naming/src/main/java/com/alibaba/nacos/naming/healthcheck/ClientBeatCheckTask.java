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
package com.alibaba.nacos.naming.healthcheck;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.annotation.JSONField;
import com.alibaba.nacos.naming.boot.RunningConfig;
import com.alibaba.nacos.naming.boot.SpringContext;
import com.alibaba.nacos.naming.core.DistroMapper;
import com.alibaba.nacos.naming.core.Instance;
import com.alibaba.nacos.naming.core.Service;
import com.alibaba.nacos.naming.healthcheck.events.InstanceHeartbeatTimeoutEvent;
import com.alibaba.nacos.naming.misc.*;
import com.alibaba.nacos.naming.push.PushService;
import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.Response;
import lombok.extern.slf4j.Slf4j;

import java.net.HttpURLConnection;
import java.util.List;


/**
 * 服务端心跳检测客户端任务
 * Check and update statues of ephemeral instances, remove them if they have been expired.
 * @author nkorange
 */
@Slf4j
public class ClientBeatCheckTask implements Runnable {

    private final Service service;

    public ClientBeatCheckTask(Service service) {
        this.service = service;
    }


    @JSONField(serialize = false)
    public PushService getPushService() {
        return SpringContext.getAppContext().getBean(PushService.class);
    }

    @JSONField(serialize = false)
    public DistroMapper getDistroMapper() {
        return SpringContext.getAppContext().getBean(DistroMapper.class);
    }

    public GlobalConfig getGlobalConfig() {
        return SpringContext.getAppContext().getBean(GlobalConfig.class);
    }

    public SwitchDomain getSwitchDomain() {
        return SpringContext.getAppContext().getBean(SwitchDomain.class);
    }

    public String taskKey() {
        return service.getName();
    }

    @Override
    public void run() {
        try {
            if (!getDistroMapper().responsible(service.getName())) {
                return;
            }

            if (!getSwitchDomain().isHealthCheckEnabled()) {
                return;
            }

            List<Instance> instances = service.allIPs(true);

            // first set health status of instances:
            for (Instance instance : instances) {
                //超时检测： 5 * 13 = 15s, 3次客户端心跳上报周期，距离最后依次超时超过3次心跳时间则执行下列逻辑，服务实例设置未不健康状态
                if (System.currentTimeMillis() - instance.getLastBeat() > instance.getInstanceHeartBeatTimeOut()) {
                    if (!instance.isMarked()) {
                        if (instance.isHealthy()) {
                            instance.setHealthy(false);
                            log.info("{POS} {IP-DISABLED} valid: {}:{}@{}@{}, region: {}, msg: client timeout after {}, last beat: {}",
                                instance.getIp(), instance.getPort(), instance.getClusterName(), service.getName(),
                                UtilsAndCommons.LOCALHOST_SITE, instance.getInstanceHeartBeatTimeOut(), instance.getLastBeat());

                            //nacos服务端发送UDP包给客户端检测服务是否正常
                            getPushService().serviceChanged(service);

                            //发送心跳超时事件
                            SpringContext.getAppContext().publishEvent(new InstanceHeartbeatTimeoutEvent(this, instance));
                        }
                    }
                }
            }

            if (!getGlobalConfig().isExpireInstance()) {
                return;
            }

            // then remove obsolete instances:
            for (Instance instance : instances) {

                if (instance.isMarked()) {
                    continue;
                }

                //具体最后1次心跳超时30s,剔除服务实例
                if (System.currentTimeMillis() - instance.getLastBeat() > instance.getIpDeleteTimeout()) {
                    // delete instance
                    log.info("[AUTO-DELETE-IP] service: {}, ip: {}", service.getName(), JSON.toJSONString(instance));
                    deleteIP(instance);
                }
            }

        } catch (Exception e) {
            log.warn("Exception while processing client beat time out.", e);
        }

    }


    private void deleteIP(Instance instance) {

        try {
            NamingProxy.Request request = NamingProxy.Request.newRequest();
            request.appendParam("ip", instance.getIp())
                .appendParam("port", String.valueOf(instance.getPort()))
                .appendParam("ephemeral", "true")
                .appendParam("clusterName", instance.getClusterName())
                .appendParam("serviceName", service.getName())
                .appendParam("namespaceId", service.getNamespaceId());

            String url = "http://127.0.0.1:" + RunningConfig.getServerPort() + RunningConfig.getContextPath()
                + UtilsAndCommons.NACOS_NAMING_CONTEXT + "/instance?" + request.toUrl();

            // delete instance asynchronously:
            HttpClient.asyncHttpDelete(url, null, null, new AsyncCompletionHandler() {
                @Override
                public Object onCompleted(Response response) throws Exception {
                    if (response.getStatusCode() != HttpURLConnection.HTTP_OK) {
                        log.error("[IP-DEAD] failed to delete ip automatically, ip: {}, caused {}, resp code: {}",
                            instance.toJSON(), response.getResponseBody(), response.getStatusCode());
                    }
                    return null;
                }
            });

        } catch (Exception e) {
            log.error("[IP-DEAD] failed to delete ip automatically, ip: {}, error: {}", instance.toJSON(), e);
        }
    }
}
