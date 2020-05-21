package com.alibaba.nacos;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

/**
 * @author zhiwei_yang
 * @time 2020-3-23-16:36
 */
@RestController
public class ServiceConsumeController {

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private FeignService feignService;

    @GetMapping("/consume")
    public String comsume(){
        return restTemplate.getForObject("http://nacos-service-registry/provider", String.class);
    }

    @GetMapping("/feign")
    public String feign(){
        return feignService.comsume();
    }
}
