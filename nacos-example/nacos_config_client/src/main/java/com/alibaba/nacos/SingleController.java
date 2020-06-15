package com.alibaba.nacos;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author zhiwei_yang
 * @time 2020-6-15-16:02
 */
@RestController
@RefreshScope
public class SingleController {

    /**
     * 不支持自动更新，需 @RefreshScope 刷新
     */
    @Value("${spring.application.name:singleValue}")
    private String singleValue;

    /**
     * 获取nacos配置
     * @return
     */
    @GetMapping("single")
    public String single(){
        return singleValue;
    }
}
