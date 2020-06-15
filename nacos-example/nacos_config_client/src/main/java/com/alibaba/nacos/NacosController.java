package com.alibaba.nacos;

import com.alibaba.nacos.api.config.annotation.NacosValue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * function: nacos配置测试控制器
 * author: zhiwei_yang
 * time: 2020/3/20-21:36
 */
@RestController

public class NacosController {

    @NacosValue(value = "${spring.application.name:nacosValue}", autoRefreshed = true)
    private String nacosValue;

    @Autowired
    private Environment environment;

    /**
     * 获取nacos配置
     * @return
     */
    @GetMapping("/env/{env}")
    public String getEnvironment(@PathVariable("env") String env){
        return environment.getProperty(env, String.class);
    }

    /**
     * 获取nacos配置
     * @return
     */
    @GetMapping("nacos")
    public String nacos(){
        return nacosValue;
    }
}
