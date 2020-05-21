package com.alibaba.nacos;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * function:
 * author: zhiwei_yang
 * time: 2020/3/20-21:36
 */
@RestController
@RefreshScope
public class NacosController {

    @Value("${application.environment:default}")
    private String environment;

    @Value("${common.description:default}")
    private String common;

    /**
     * 私有配置
     * @return
     */
    @GetMapping("/config")
    public String getEnvironment(){
        return environment;
    }

    /**
     * 公共配置
     * @return
     */
    @GetMapping("/common")
    public String getcommon(){
        return common;
    }
}
