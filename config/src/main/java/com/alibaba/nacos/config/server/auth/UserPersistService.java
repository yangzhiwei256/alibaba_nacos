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
import com.alibaba.nacos.config.server.model.User;
import com.alibaba.nacos.config.server.service.PersistService;
import com.alibaba.nacos.config.server.utils.PaginationHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;


/**
 * User CRUD service
 *
 * @author nkorange
 * @since 1.2.0
 */
@Service
@Slf4j
public class UserPersistService extends PersistService {

    public void createUser(String username, String password) {
        String sql = "INSERT into nacos.users (username, password, enabled) VALUES (?, ?, ?)";

        try {
            jdbcTemplate.update(sql, username, password, true);
        } catch (CannotGetJdbcConnectionException e) {
            log.error("[db-error] " + e.toString(), e);
            throw e;
        }
    }

    public void deleteUser(String username) {
        String sql = "DELETE from nacos.users WHERE username=?";
        try {
            jdbcTemplate.update(sql, username);
        } catch (CannotGetJdbcConnectionException e) {
            log.error("[db-error] " + e.toString(), e);
            throw e;
        }
    }

    public void updateUserPassword(String username, String password) {
        try {
            jdbcTemplate.update(
                "UPDATE nacos.users SET password = ? WHERE username=?",
                password, username);
        } catch (CannotGetJdbcConnectionException e) {
            log.error("[db-error] " + e.toString(), e);
            throw e;
        }
    }

    public User findUserByUsername(String username) {
        String sql = "SELECT username,password FROM nacos.users WHERE username=? ";
        try {
            return this.jdbcTemplate.queryForObject(sql, new Object[]{username}, USER_ROW_MAPPER);
        } catch (CannotGetJdbcConnectionException e) {
            log.error("[db-error] " + e.toString(), e);
            throw e;
        } catch (EmptyResultDataAccessException e) {
            return null;
        } catch (Exception e) {
            log.error("[db-other-error]" + e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public Page<User> getUsers(int pageNo, int pageSize) {

        PaginationHelper<User> helper = new PaginationHelper<>();

        String sqlCountRows = "select count(*) from nacos.users where ";
        String sqlFetchRows
            = "select username,password from nacos.users where ";

        String where = " 1=1 ";

        try {
            Page<User> pageInfo = helper.fetchPage(jdbcTemplate, sqlCountRows
                    + where, sqlFetchRows + where, new ArrayList<String>().toArray(), pageNo,
                pageSize, USER_ROW_MAPPER);
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

    private static final class UserRowMapper implements
        RowMapper<User> {
        @Override
        public User mapRow(ResultSet rs, int rowNum)
            throws SQLException {
            User info = new User();
            info.setUsername(rs.getString("username"));
            info.setPassword(rs.getString("password"));
            return info;
        }
    }

    private static final UserRowMapper USER_ROW_MAPPER = new UserRowMapper();

}
