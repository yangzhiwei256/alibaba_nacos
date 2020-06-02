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
package com.alibaba.nacos.console.security.nacos;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.nacos.api.common.Constants;
import com.alibaba.nacos.client.naming.utils.CollectionUtils;
import com.alibaba.nacos.common.constant.CommonConstants;
import com.alibaba.nacos.config.server.auth.UserRoleInfo;
import com.alibaba.nacos.console.security.nacos.roles.NacosRoleServiceImpl;
import com.alibaba.nacos.console.security.nacos.users.NacosUser;
import com.alibaba.nacos.core.auth.AccessException;
import com.alibaba.nacos.core.auth.AuthManager;
import com.alibaba.nacos.core.auth.Permission;
import com.alibaba.nacos.core.auth.User;
import io.jsonwebtoken.ExpiredJwtException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builtin access control entry of Nacos
 *
 * @author nkorange
 * @since 1.2.0
 */
@Component
@Slf4j
public class NacosAuthManager implements AuthManager {

    private static final String TOKEN_PREFIX = "Bearer ";

    @Autowired
    private JwtTokenManager tokenManager;

    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private NacosRoleServiceImpl roleService;

    @Override
    public User login(Object request) throws AccessException {
        HttpServletRequest req = (HttpServletRequest) request;
        String token = resolveToken(req);
        if (StringUtils.isBlank(token)) {
            throw new AccessException("user not found!");
        }

        try {
            tokenManager.validateToken(token);
        } catch (ExpiredJwtException e) {
            throw new AccessException("token expired!");
        } catch (Exception e) {
            throw new AccessException("token invalid!");
        }

        Authentication authentication = tokenManager.getAuthentication(token);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        String username = authentication.getName();
        NacosUser user = new NacosUser();
        user.setUserName(username);
        user.setToken(token);
        List<UserRoleInfo> userRoleInfoList = roleService.getRoles(username);
        if(CollectionUtils.isEmpty(userRoleInfoList)){
            throw new AccessException("user role invalid!");
        }

        String channel = req.getHeader(CommonConstants.LOGIN_CHANNEL);
        boolean isAppChannel = userRoleInfoList.stream().map(UserRoleInfo::getRole).collect(Collectors.toList()).contains(CommonConstants.APP_LOGIN_ROLE);

        //APP渠道不能登陆nacos管理后台
        if(isAppChannel && StringUtils.isEmpty(channel)){
            throw new AccessException("user login channel invalid!");
        }

        for (UserRoleInfo userRoleInfo : userRoleInfoList) {
            if (userRoleInfo.getRole().equals(NacosRoleServiceImpl.GLOBAL_ADMIN_ROLE)) {
                user.setGlobalAdmin(true);
                break;
            }
        }

        return user;
    }

    @Override
    public void auth(Permission permission, User user) throws AccessException {
        if (log.isDebugEnabled()) {
            log.debug("auth permission: {}, user: {}", permission, user);
        }

        if (!roleService.hasPermission(user.getUserName(), permission)) {
            throw new AccessException("authorization failed!");
        }
    }

    /**
     * Get token from header
     */
    private String resolveToken(HttpServletRequest request) throws AccessException {
        String bearerToken = request.getHeader(NacosAuthConfig.AUTHORIZATION_HEADER);
        if(StringUtils.isNotBlank(bearerToken) && bearerToken.contains("null")){ //TODO 定位null设置出处
            return StringUtils.EMPTY;
        }
        if (StringUtils.isNotBlank(bearerToken) && bearerToken.startsWith(TOKEN_PREFIX)) {
            return bearerToken.substring(7);
        }
        //修复 accessToken存储在TokenMap bug
        if (StringUtils.isNotBlank(bearerToken) && !bearerToken.startsWith(TOKEN_PREFIX)) {
            JSONObject jsonObject = JSONObject.parseObject(bearerToken);
            return jsonObject.getString(Constants.ACCESS_TOKEN);
        }
        bearerToken = request.getParameter(Constants.ACCESS_TOKEN);
        if (StringUtils.isBlank(bearerToken)) {
            String userName = request.getParameter("username");
            String password = request.getParameter("password");
            bearerToken = resolveTokenFromUser(userName, password);
        }

        return bearerToken;
    }

    private String resolveTokenFromUser(String userName, String rawPassword) throws AccessException {

        try {
            UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(userName, rawPassword);
            authenticationManager.authenticate(authenticationToken);
        } catch (AuthenticationException e) {
            throw new AccessException("unknown user!");
        }

        return tokenManager.createToken(userName);
    }
}
