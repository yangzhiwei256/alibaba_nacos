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
package com.alibaba.nacos.client.security;


import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.common.Constants;
import com.alibaba.nacos.client.naming.net.HttpClient;
import com.alibaba.nacos.common.utils.HttpMethod;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.Charsets;
import org.apache.commons.lang3.StringUtils;

import java.net.HttpURLConnection;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Security proxy to update security information
 * 安全代理，封装nacos客户端访问nacos集群的安全信息
 * @author nkorange
 * @since 1.2.0
 */
@Slf4j
public class SecurityProxy {

    private static final String LOGIN_URL = "/v1/auth/users/login";

    private final String contextPath;

    /**
     * User's name
     */
    private final String username;

    /**
     * User's password
     */
    private final String password;

    /**
     * A token to take with when sending request to Nacos server
     */
    private String accessToken;

    /**
     * TTL of token in seconds
     */
    private long tokenTtl;

    /**
     * Last timestamp refresh security info from server
     */
    private long lastRefreshTime;

    /**
     * time window to refresh security info in seconds
     */
    private long tokenRefreshWindow;

    /**
     * Construct from properties, keeping flexibility
     *
     * @param properties a bunch of properties to read
     */
    public SecurityProxy(Properties properties) {
        username = properties.getProperty(PropertyKeyConst.USERNAME, StringUtils.EMPTY);
        password = properties.getProperty(PropertyKeyConst.PASSWORD, StringUtils.EMPTY);
        contextPath = StringUtils.isEmpty(properties.getProperty(PropertyKeyConst.CONTEXT_PATH)) ?
            "/nacos" : properties.getProperty(PropertyKeyConst.CONTEXT_PATH);
    }

    /**
     * nacos客户端登陆nacos服务器，刷新Token，便于后续交互
     * @param servers
     * @return
     */
    public boolean login(List<String> servers) {

        // 判断当前时间是否到过期时间节点前 tokenTtl/10, 过期时间节点前 tokenTtl/10 内重新执行登陆操作，刷新Token、
        if ((System.currentTimeMillis() - lastRefreshTime) < TimeUnit.SECONDS.toMillis(tokenTtl - tokenRefreshWindow)) {
            return true;
        }

        //nacos集群任意节点登陆登陆成功直接返回
        for (String server : servers) {
            if (login(server)) {
                lastRefreshTime = System.currentTimeMillis();
                return true;
            }
        }
        return false;
    }

    /**
     * 登陆Nacos 服务器
     * @param server 服务器地址
     * @return
     */
    public boolean login(String server) {

        if (StringUtils.isNotBlank(username)) {
            Map<String, String> params = new HashMap<String, String>(2);
            params.put("username", username);
            String body = "password=" + password;
            String url = "http://" + server + contextPath + LOGIN_URL;

            if (server.contains(Constants.HTTP_PREFIX)) {
                url = server + contextPath + LOGIN_URL;
            }

            HttpClient.HttpResult result = HttpClient.request(url, new ArrayList<String>(2),
                params, body, Charsets.UTF_8.name(), HttpMethod.POST);

            if (result.code != HttpURLConnection.HTTP_OK) {
                log.error("login failed: {}", JSON.toJSONString(result));
                return false;
            }

            JSONObject obj = JSON.parseObject(result.content);
            if (obj.containsKey(Constants.ACCESS_TOKEN)) {
                accessToken = obj.getString(Constants.ACCESS_TOKEN);
                tokenTtl = obj.getIntValue(Constants.TOKEN_TTL);
                tokenRefreshWindow = tokenTtl / 10;
            }
        }
        return true;
    }

    public String getAccessToken() {
        return accessToken;
    }


}
