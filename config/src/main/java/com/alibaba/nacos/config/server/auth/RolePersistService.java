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
package com.alibaba.nacos.config.server.auth;

import com.alibaba.nacos.config.server.model.Page;
import com.alibaba.nacos.config.server.service.PersistService;
import com.alibaba.nacos.config.server.utils.PaginationHelper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;


/**
 * Role CRUD service
 *
 * @author nkorange
 * @since 1.2.0
 */
@Service
@Slf4j
public class RolePersistService extends PersistService {


    public Page<UserRoleInfo> getRoles(int pageNo, int pageSize) {

        PaginationHelper<UserRoleInfo> helper = new PaginationHelper<>();

        String sqlCountRows = "select count(*) from (select distinct role from roles) roles where ";
        String sqlFetchRows
            = "select role,username from roles where ";

        String where = " 1=1 ";

        try {
            Page<UserRoleInfo> pageInfo = helper.fetchPage(jdbcTemplate, sqlCountRows
                    + where, sqlFetchRows + where, new ArrayList<String>().toArray(), pageNo,
                pageSize, ROLE_INFO_ROW_MAPPER);
            if (pageInfo == null) {
                pageInfo = new Page<>();
                pageInfo.setTotalCount(0);
                pageInfo.setPageItems(new ArrayList<>());
            }
            return pageInfo;
        } catch (CannotGetJdbcConnectionException e) {
            log.error("[db-error] " + e.toString(), e);
            throw e;
        }
    }

    public Page<UserRoleInfo> getRolesByUserName(String username, int pageNo, int pageSize) {

        PaginationHelper<UserRoleInfo> helper = new PaginationHelper<>();

        String sqlCountRows = "select count(*) from roles where ";
        String sqlFetchRows
            = "select role,username from roles where ";

        String where = " username='" + username + "' ";

        if (StringUtils.isBlank(username)) {
            where = " 1=1 ";
        }

        try {
            return helper.fetchPage(jdbcTemplate, sqlCountRows
                    + where, sqlFetchRows + where, new ArrayList<String>().toArray(), pageNo,
                pageSize, ROLE_INFO_ROW_MAPPER);
        } catch (CannotGetJdbcConnectionException e) {
            log.error("[db-error] " + e.toString(), e);
            throw e;
        }
    }

    public void addRole(String role, String userName) {

        String sql = "INSERT into roles (role, username) VALUES (?, ?)";

        try {
            jdbcTemplate.update(sql, role, userName);
        } catch (CannotGetJdbcConnectionException e) {
            log.error("[db-error] " + e.toString(), e);
            throw e;
        }
    }

    public void deleteRole(String role) {
        String sql = "DELETE from roles WHERE role=?";
        try {
            jdbcTemplate.update(sql, role);
        } catch (CannotGetJdbcConnectionException e) {
            log.error("[db-error] " + e.toString(), e);
            throw e;
        }
    }

    public void deleteRole(String role, String username) {
        String sql = "DELETE from roles WHERE role=? and username=?";
        try {
            jdbcTemplate.update(sql, role, username);
        } catch (CannotGetJdbcConnectionException e) {
            log.error("[db-error] " + e.toString(), e);
            throw e;
        }
    }

    private static final class RoleInfoRowMapper implements
        RowMapper<UserRoleInfo> {
        @Override
        public UserRoleInfo mapRow(ResultSet rs, int rowNum)
            throws SQLException {
            UserRoleInfo userRoleInfo = new UserRoleInfo();
            userRoleInfo.setRole(rs.getString("role"));
            userRoleInfo.setUsername(rs.getString("username"));
            return userRoleInfo;
        }
    }

    private static final RoleInfoRowMapper ROLE_INFO_ROW_MAPPER = new RoleInfoRowMapper();
}
