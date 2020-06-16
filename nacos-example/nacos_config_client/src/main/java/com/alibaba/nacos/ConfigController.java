package com.alibaba.nacos;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RefreshScope 作用到配置自动装配类
 * @author zhiwei_yang
 * @time 2020-6-16-10:24
 */
@RestController
@RefreshScope
public class ConfigController {

    @Autowired
    private NacosConfig nacosConfig;

    @GetMapping("/config")
    public String config(){
        return nacosConfig.getName();
    }
}
