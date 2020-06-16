package com.alibaba.nacos;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * @author zhiwei_yang
 * @time 2020-6-16-10:22
 */
@Configuration
@ConfigurationProperties("nacos.config")
@Data
public class NacosConfig{

    private String name;
}
