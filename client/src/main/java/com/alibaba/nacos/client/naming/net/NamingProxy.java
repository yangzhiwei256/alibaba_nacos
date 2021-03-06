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
package com.alibaba.nacos.client.naming.net;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.SystemPropertyKeyConst;
import com.alibaba.nacos.api.common.Constants;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.CommonParams;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.alibaba.nacos.api.naming.pojo.ListView;
import com.alibaba.nacos.api.naming.pojo.Service;
import com.alibaba.nacos.api.selector.AbstractSelector;
import com.alibaba.nacos.api.selector.ExpressionSelector;
import com.alibaba.nacos.api.selector.SelectorType;
import com.alibaba.nacos.client.config.impl.SpasAdapter;
import com.alibaba.nacos.client.monitor.MetricsMonitor;
import com.alibaba.nacos.client.naming.beat.BeatInfo;
import com.alibaba.nacos.client.naming.utils.CollectionUtils;
import com.alibaba.nacos.client.naming.utils.SignUtil;
import com.alibaba.nacos.client.naming.utils.UtilAndComs;
import com.alibaba.nacos.client.security.SecurityProxy;
import com.alibaba.nacos.client.utils.AppNameUtils;
import com.alibaba.nacos.client.utils.TemplateUtils;
import com.alibaba.nacos.common.constant.CommonConstants;
import com.alibaba.nacos.common.utils.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;


/**
 * @author nkorange
 */
@Slf4j
public class NamingProxy {

    private static final int DEFAULT_SERVER_PORT = 8848;

    private int serverPort = DEFAULT_SERVER_PORT;

    private final String namespaceId;

    private final String endpoint;

    private String nacosDomain;

    private List<String> serverList;

    private List<String> serversFromEndpoint = new ArrayList<String>();

    private final SecurityProxy securityProxy;

    private long lastSrvRefTime = 0L;

    private final long vipSrvRefInterMillis = TimeUnit.SECONDS.toMillis(30);

    private final long securityInfoRefreshIntervalMills = TimeUnit.SECONDS.toMillis(5);

    /** 故障节点端口检测最长耗时  **/
    private final int faultNodeCheckTimeoutMs = 300;

    /** 临时故障节点： 避免nacos节点出现故障，轮询到故障节点，通过定时任务检测恢复 **/
    private final Set<String> realTimeFaultNode = new CopyOnWriteArraySet<>();

    private Properties properties;

    public NamingProxy(String namespaceId, String endpoint, String serverList, Properties properties) {

        securityProxy = new SecurityProxy(properties);
        this.properties = properties;
        this.setServerPort(DEFAULT_SERVER_PORT);
        this.namespaceId = namespaceId;
        this.endpoint = endpoint;
        if (StringUtils.isNotEmpty(serverList)) {
            this.serverList = Arrays.asList(serverList.split(","));
            if (this.serverList.size() == 1) {
                this.nacosDomain = serverList;
            }
        }

        initRefreshTask();
    }

    /**
     * 初始化刷新服务列表任务，并模拟登陆获取Token
     */
    private void initRefreshTask() {

        ScheduledExecutorService executorService = new ScheduledThreadPoolExecutor(3, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r);
                t.setName("com.alibaba.nacos.client.naming.updater");
                t.setDaemon(true);
                return t;
            }
        });

        executorService.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                refreshSrvIfNeed();
            }
        }, 0, vipSrvRefInterMillis, TimeUnit.MILLISECONDS);


        executorService.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                securityProxy.login(getServerList());
            }
        }, 0, securityInfoRefreshIntervalMills, TimeUnit.MILLISECONDS);


        executorService.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                //剔除故障节点
                realTimeFaultNode.removeIf(faultHost -> NetUtils.isPortUseful(faultHost.split(":")[0],
                        Integer.parseInt(faultHost.split(":")[1]), faultNodeCheckTimeoutMs));
            }
        }, 0, securityInfoRefreshIntervalMills, TimeUnit.MILLISECONDS);

        refreshSrvIfNeed();
        securityProxy.login(getServerList());
    }

    public List<String> getServerListFromEndpoint() {

        try {
            String urlString = "http://" + endpoint + "/nacos/serverlist";
            List<String> headers = builderHeaders();

            HttpClient.HttpResult result = HttpClient.httpGet(urlString, headers, null, UtilAndComs.ENCODING);
            if (HttpURLConnection.HTTP_OK != result.code) {
                throw new IOException("Error while requesting: " + urlString + "'. Server returned: "
                    + result.code);
            }

            String content = result.content;
            List<String> list = new ArrayList<String>();
            for (String line : IoUtils.readLines(new StringReader(content))) {
                if (!line.trim().isEmpty()) {
                    list.add(line.trim());
                }
            }

            return list;

        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    private void refreshSrvIfNeed() {
        try {

            if (!CollectionUtils.isEmpty(serverList)) {
                log.debug("server list provided by user: " + serverList);
                return;
            }

            if (System.currentTimeMillis() - lastSrvRefTime < vipSrvRefInterMillis) {
                return;
            }

            List<String> list = getServerListFromEndpoint();

            if (CollectionUtils.isEmpty(list)) {
                throw new Exception("Can not acquire Nacos list");
            }

            if (!CollectionUtils.isEqualCollection(list, serversFromEndpoint)) {
                log.info("[SERVER-LIST] server list is updated: " + list);
            }

            serversFromEndpoint = list;
            lastSrvRefTime = System.currentTimeMillis();
        } catch (Throwable e) {
            log.warn("failed to update server list", e);
        }
    }

    public void registerService(String serviceName, String groupName, Instance instance) throws NacosException {

        log.info("[REGISTER-SERVICE] {} registering service {} with instance: {}", namespaceId, serviceName, instance);

        final Map<String, String> params = new HashMap<String, String>(9);
        params.put(CommonParams.NAMESPACE_ID, namespaceId);
        params.put(CommonParams.SERVICE_NAME, serviceName);
        params.put(CommonParams.GROUP_NAME, groupName);
        params.put(CommonParams.CLUSTER_NAME, instance.getClusterName());
        params.put("ip", instance.getIp());
        params.put("port", String.valueOf(instance.getPort()));
        params.put("weight", String.valueOf(instance.getWeight()));
        params.put("enable", String.valueOf(instance.isEnabled()));
        params.put("healthy", String.valueOf(instance.isHealthy()));
        params.put("ephemeral", String.valueOf(instance.isEphemeral()));
        params.put("metadata", JSON.toJSONString(instance.getMetadata()));
        reqAPI(UtilAndComs.NACOS_URL_INSTANCE, params, HttpMethod.POST);
    }

    public void deregisterService(String serviceName, Instance instance) throws NacosException {

        log.info("[DEREGISTER-SERVICE] {} deregistering service {} with instance: {}",
            namespaceId, serviceName, instance);

        final Map<String, String> params = new HashMap<String, String>(8);
        params.put(CommonParams.NAMESPACE_ID, namespaceId);
        params.put(CommonParams.SERVICE_NAME, serviceName);
        params.put(CommonParams.CLUSTER_NAME, instance.getClusterName());
        params.put("ip", instance.getIp());
        params.put("port", String.valueOf(instance.getPort()));
        params.put("ephemeral", String.valueOf(instance.isEphemeral()));

        reqAPI(UtilAndComs.NACOS_URL_INSTANCE, params, HttpMethod.DELETE);
    }

    public void updateInstance(String serviceName, String groupName, Instance instance) throws NacosException {
        log.info("[UPDATE-SERVICE] {} update service {} with instance: {}",
            namespaceId, serviceName, instance);

        final Map<String, String> params = new HashMap<String, String>(8);
        params.put(CommonParams.NAMESPACE_ID, namespaceId);
        params.put(CommonParams.SERVICE_NAME, serviceName);
        params.put(CommonParams.GROUP_NAME, groupName);
        params.put(CommonParams.CLUSTER_NAME, instance.getClusterName());
        params.put("ip", instance.getIp());
        params.put("port", String.valueOf(instance.getPort()));
        params.put("weight", String.valueOf(instance.getWeight()));
        params.put("enabled", String.valueOf(instance.isEnabled()));
        params.put("ephemeral", String.valueOf(instance.isEphemeral()));
        params.put("metadata", JSON.toJSONString(instance.getMetadata()));

        reqAPI(UtilAndComs.NACOS_URL_INSTANCE, params, HttpMethod.PUT);
    }

    public Service queryService(String serviceName, String groupName) throws NacosException {
        log.info("[QUERY-SERVICE] {} query service : {}, {}",
            namespaceId, serviceName, groupName);

        final Map<String, String> params = new HashMap<String, String>(3);
        params.put(CommonParams.NAMESPACE_ID, namespaceId);
        params.put(CommonParams.SERVICE_NAME, serviceName);
        params.put(CommonParams.GROUP_NAME, groupName);

        String result = reqAPI(UtilAndComs.NACOS_URL_SERVICE, params, HttpMethod.GET);
        JSONObject jsonObject = JSON.parseObject(result);
        return jsonObject.toJavaObject(Service.class);
    }

    public void createService(Service service, AbstractSelector selector) throws NacosException {

        log.info("[CREATE-SERVICE] {} creating service : {}",
            namespaceId, service);

        final Map<String, String> params = new HashMap<String, String>(6);
        params.put(CommonParams.NAMESPACE_ID, namespaceId);
        params.put(CommonParams.SERVICE_NAME, service.getName());
        params.put(CommonParams.GROUP_NAME, service.getGroupName());
        params.put("protectThreshold", String.valueOf(service.getProtectThreshold()));
        params.put("metadata", JSON.toJSONString(service.getMetadata()));
        params.put("selector", JSON.toJSONString(selector));

        reqAPI(UtilAndComs.NACOS_URL_SERVICE, params, HttpMethod.POST);

    }

    public boolean deleteService(String serviceName, String groupName) throws NacosException {
        log.info("[DELETE-SERVICE] {} deleting service : {} with groupName : {}",
            namespaceId, serviceName, groupName);

        final Map<String, String> params = new HashMap<String, String>(6);
        params.put(CommonParams.NAMESPACE_ID, namespaceId);
        params.put(CommonParams.SERVICE_NAME, serviceName);
        params.put(CommonParams.GROUP_NAME, groupName);

        String result = reqAPI(UtilAndComs.NACOS_URL_SERVICE, params, HttpMethod.DELETE);
        return "ok".equals(result);
    }

    public void updateService(Service service, AbstractSelector selector) throws NacosException {
        log.info("[UPDATE-SERVICE] {} updating service : {}",
            namespaceId, service);

        final Map<String, String> params = new HashMap<String, String>(6);
        params.put(CommonParams.NAMESPACE_ID, namespaceId);
        params.put(CommonParams.SERVICE_NAME, service.getName());
        params.put(CommonParams.GROUP_NAME, service.getGroupName());
        params.put("protectThreshold", String.valueOf(service.getProtectThreshold()));
        params.put("metadata", JSON.toJSONString(service.getMetadata()));
        params.put("selector", JSON.toJSONString(selector));

        reqAPI(UtilAndComs.NACOS_URL_SERVICE, params, HttpMethod.PUT);
    }

    public String queryList(String serviceName, String clusters, int udpPort, boolean healthyOnly)
        throws NacosException {

        final Map<String, String> params = new HashMap<String, String>(8);
        params.put(CommonParams.NAMESPACE_ID, namespaceId);
        params.put(CommonParams.SERVICE_NAME, serviceName);
        params.put("clusters", clusters);
        params.put("udpPort", String.valueOf(udpPort));
        params.put("clientIP", NetUtils.localIP());
        params.put("healthyOnly", String.valueOf(healthyOnly));

        return reqAPI(UtilAndComs.NACOS_URL_BASE + "/instance/list", params, HttpMethod.GET);
    }

    /**
     * 发送客户端服务心跳
     * @param beatInfo 服务心跳信息
     * @param lightBeatEnabled
     * @return
     * @throws NacosException
     */
    public JSONObject sendBeat(BeatInfo beatInfo, boolean lightBeatEnabled) throws NacosException {

        if (log.isDebugEnabled()) {
            log.debug("[BEAT] {} sending heart beat to server: {}", namespaceId, beatInfo.toString());
        }
        Map<String, String> params = new HashMap<String, String>(8);
        String body = StringUtils.EMPTY;
        if (!lightBeatEnabled) {
            body = "beat=" + JSON.toJSONString(beatInfo);
        }
        params.put(CommonParams.NAMESPACE_ID, namespaceId);
        params.put(CommonParams.SERVICE_NAME, beatInfo.getServiceName());
        params.put(CommonParams.CLUSTER_NAME, beatInfo.getCluster());
        params.put("ip", beatInfo.getIp());
        params.put("port", String.valueOf(beatInfo.getPort()));
        String result = reqAPI(UtilAndComs.NACOS_URL_BASE + "/instance/beat", params, body, HttpMethod.PUT);
        return JSON.parseObject(result);
    }

    public boolean serverHealthy() {

        try {
            String result = reqAPI(UtilAndComs.NACOS_URL_BASE + "/operator/metrics",
                new HashMap<String, String>(2), HttpMethod.GET);
            JSONObject json = JSON.parseObject(result);
            String serverStatus = json.getString("status");
            return "UP".equals(serverStatus);
        } catch (Exception e) {
            return false;
        }
    }

    public ListView<String> getServiceList(int pageNo, int pageSize, String groupName) throws NacosException {
        return getServiceList(pageNo, pageSize, groupName, null);
    }

    public ListView<String> getServiceList(int pageNo, int pageSize, String groupName, AbstractSelector selector) throws NacosException {

        Map<String, String> params = new HashMap<String, String>(4);
        params.put("pageNo", String.valueOf(pageNo));
        params.put("pageSize", String.valueOf(pageSize));
        params.put(CommonParams.NAMESPACE_ID, namespaceId);
        params.put(CommonParams.GROUP_NAME, groupName);

        if (selector != null) {
            switch (SelectorType.valueOf(selector.getType())) {
                case none:
                    break;
                case label:
                    ExpressionSelector expressionSelector = (ExpressionSelector) selector;
                    params.put("selector", JSON.toJSONString(expressionSelector));
                    break;
                default:
                    break;
            }
        }

        String result = reqAPI(UtilAndComs.NACOS_URL_BASE + "/service/list", params, HttpMethod.GET);
        JSONObject json = JSON.parseObject(result);
        ListView<String> listView = new ListView<String>();
        listView.setCount(json.getInteger("count"));
        listView.setData(JSON.parseObject(json.getString("doms"), new TypeReference<List<String>>() {
        }));

        return listView;
    }

    public String reqAPI(String api, Map<String, String> params, String method) throws NacosException {
        return reqAPI(api, params, StringUtils.EMPTY, method);
    }

    public String reqAPI(String api, Map<String, String> params, String body, String method) throws NacosException {
        return reqAPI(api, params, body, getServerList(), method);
    }

    private List<String> getServerList() {
        List<String> snapshot = serversFromEndpoint;
        if (!CollectionUtils.isEmpty(serverList)) {
            snapshot = serverList;
        }
        return snapshot;
    }

    public String callServer(String api, Map<String, String> params, String body, String curServer) throws NacosException {
        return callServer(api, params, body, curServer, HttpMethod.GET);
    }

    /**
     * 调用远程Nacos服务
     * @param api nacos服务URI
     * @param params 请求参数
     * @param body 请求体
     * @param curServer nacos服务器地址
     * @param method http方法
     * @return
     * @throws NacosException
     */
    public String callServer(String api, Map<String, String> params, String body, String curServer, String method)
        throws NacosException {
        long start = System.currentTimeMillis();
        long end = 0;
        injectSecurityInfo(params);
        List<String> headers = builderHeaders();

        String url;
        if (curServer.startsWith(UtilAndComs.HTTPS) || curServer.startsWith(UtilAndComs.HTTP)) {
            url = curServer + api;
        } else {
            if (!curServer.contains(UtilAndComs.SERVER_ADDR_IP_SPLITER)) {
                curServer = curServer + UtilAndComs.SERVER_ADDR_IP_SPLITER + serverPort;
            }
            url = HttpClient.getPrefix() + curServer + api;
        }

        HttpClient.HttpResult result = HttpClient.request(url, headers, params, body, UtilAndComs.ENCODING, method);
        end = System.currentTimeMillis();

        MetricsMonitor.getNamingRequestMonitor(method, url, String.valueOf(result.code))
            .observe(end - start);

        if (HttpURLConnection.HTTP_OK == result.code) {
            return result.content;
        }

        if (HttpURLConnection.HTTP_NOT_MODIFIED == result.code) {
            return StringUtils.EMPTY;
        }

        throw new NacosException(result.code, result.content);
    }

    /** nacos服务请求API **/
    public String reqAPI(String api, Map<String, String> params, String body, List<String> servers, String method) throws NacosException {

        params.put(CommonParams.NAMESPACE_ID, getNamespaceId());
        if (CollectionUtils.isEmpty(servers) && StringUtils.isEmpty(nacosDomain)) {
            throw new NacosException(NacosException.INVALID_PARAM, "no server available");
        }

        NacosException exception = new NacosException();
        if (!CollectionUtils.isEmpty(servers)) {
            Random random = new Random(System.currentTimeMillis());

            //过滤故障节点，只轮询有效节点
            List<String> effectiveServerList = servers.stream().filter(node -> !realTimeFaultNode.contains(node)).collect(Collectors.toList());

            int index = random.nextInt(effectiveServerList.size());
            for (int i = 0; i < effectiveServerList.size(); i++) {
                String server = effectiveServerList.get(index);
                try {
                    if(realTimeFaultNode.contains(server)){
                        continue;
                    }
                    return callServer(api, params, body, server, method);
                } catch (NacosException e) {
                    exception = e;
                    if (log.isDebugEnabled()) {
                        log.debug("request {} failed.", server, e);
                    }
                    //添加故障节点
                    realTimeFaultNode.add(server);
                }
                index = (index + 1) % effectiveServerList.size();
            }
        }

        if (StringUtils.isNotBlank(nacosDomain)) {
            for (int i = 0; i < UtilAndComs.REQUEST_DOMAIN_RETRY_COUNT; i++) {
                try {
                    return callServer(api, params, body, nacosDomain, method);
                } catch (NacosException e) {
                    exception = e;
                    if (log.isDebugEnabled()) {
                        log.debug("request {} failed.", nacosDomain, e);
                    }
                }
            }
        }
        log.error("request: {} failed, servers: {}, code: {}, msg: {}", api, servers, exception.getErrCode(), exception.getErrMsg());
        throw new NacosException(exception.getErrCode(), "failed to req API:/api/" + api + " after all servers(" + servers + ") tried: "
            + exception.getMessage());

    }

    /**
     * 请求封装安全信息（例如 用户名、密码、token等）
     * @param params
     */
    private void injectSecurityInfo(Map<String, String> params) {

        // 封装Token
        if (StringUtils.isNotBlank(securityProxy.getAccessToken())) {
            params.put(Constants.ACCESS_TOKEN, securityProxy.getAccessToken());
        }

        // Inject ak/sk if exist:
        String accessKey = getAccessKey();
        String secretKey = getSecretKey();
        params.put("app", AppNameUtils.getAppName());
        if (StringUtils.isNotBlank(accessKey) && StringUtils.isNotBlank(secretKey)) {
            try {
                String signData = getSignData(params.get("serviceName"));
                String signature = SignUtil.sign(signData, secretKey);
                params.put("signature", signature);
                params.put("data", signData);
                params.put("ak", accessKey);
            } catch (Exception e) {
                log.error("inject ak/sk failed.", e);
            }
        }
    }

    public List<String> builderHeaders() {
        List<String> headers = Arrays.asList(
            CommonConstants.CLIENT_VERSION_HEADER, VersionUtils.VERSION,
            CommonConstants.USER_AGENT_HEADER, UtilAndComs.VERSION,
            "Accept-Encoding", "gzip,deflate,sdch",
            "Connection", "Keep-Alive",
            "RequestId", UuidUtils.generateUuid(), "Request-Module", "Naming");
        return headers;
    }

    private static String getSignData(String serviceName) {
        return StringUtils.isNotEmpty(serviceName)
            ? System.currentTimeMillis() + "@@" + serviceName
            : String.valueOf(System.currentTimeMillis());
    }

    public String getAccessKey() {
        if (properties == null) {
            return SpasAdapter.getAk();
        }

        return TemplateUtils.stringEmptyAndThenExecute(properties.getProperty(PropertyKeyConst.ACCESS_KEY), new Callable<String>() {
            @Override
            public String call() {
                return SpasAdapter.getAk();
            }
        });
    }

    public String getSecretKey() {
        if (properties == null) {

            return SpasAdapter.getSk();
        }

        return TemplateUtils.stringEmptyAndThenExecute(properties.getProperty(PropertyKeyConst.SECRET_KEY), new Callable<String>() {
            @Override
            public String call() throws Exception {
                return SpasAdapter.getSk();
            }
        });
    }

    public void setProperties(Properties properties) {
        this.properties = properties;
        setServerPort(DEFAULT_SERVER_PORT);
    }

    public String getNamespaceId() {
        return namespaceId;
    }

    public void setServerPort(int serverPort) {
        this.serverPort = serverPort;

        String sp = System.getProperty(SystemPropertyKeyConst.NAMING_SERVER_PORT);
        if (StringUtils.isNotBlank(sp)) {
            this.serverPort = Integer.parseInt(sp);
        }
    }

}

