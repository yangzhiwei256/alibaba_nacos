package com.alibaba.nacos.config.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 数据库配置
 * @author zhiwei_yang
 * @time 2020-6-2-10:43
 */
@Configuration
@ConfigurationProperties(prefix = "nacos.datasource")
public class DataSourceConfigProperties {

    /** 初始连接大小 **/
    private Integer initialSize = 10;

    /** 最大活跃连接大小 **/
    private Integer maxActive = 20;

    /** 最大空闲连接 **/
    private Integer maxIdle = 50;

    /** 最长等待时间 **/
    private Long maxWait = 3000L;

    /** 数据库默认用户名(集群) **/
    private String username;

    /** 数据库默认密码(集群) **/
    private String password;

    /** 数据库单体配置 **/
    private List<DatabaseProperties> db;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Integer getInitialSize() {
        return initialSize;
    }

    public void setInitialSize(Integer initialSize) {
        this.initialSize = initialSize;
    }

    public Integer getMaxActive() {
        return maxActive;
    }

    public void setMaxActive(Integer maxActive) {
        this.maxActive = maxActive;
    }

    public Integer getMaxIdle() {
        return maxIdle;
    }

    public void setMaxIdle(Integer maxIdle) {
        this.maxIdle = maxIdle;
    }

    public Long getMaxWait() {
        return maxWait;
    }

    public void setMaxWait(Long maxWait) {
        this.maxWait = maxWait;
    }

    public List<DatabaseProperties> getDb() {
        return db;
    }

    public void setDb(List<DatabaseProperties> db) {
        this.db = db;
    }
}
