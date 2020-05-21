package com.alibaba.nacos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * @author zhiwei_yang
 * @time 2020-3-23-16:34
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients
public class ServiceConsumeApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServiceConsumeApplication.class, args);
    }
}
