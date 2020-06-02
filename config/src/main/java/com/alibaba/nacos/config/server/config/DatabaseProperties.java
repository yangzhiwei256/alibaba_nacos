package com.alibaba.nacos.config.server.config;

/**
 * 数据库属性
 */
public class DatabaseProperties {

    /** 数据库URL **/
    private String url;

    /** 数据库名 **/
    private String username;

    /** 数据库密码 **/
    private String password;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

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
}