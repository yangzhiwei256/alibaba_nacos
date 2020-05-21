package com.alibaba.nacos;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * @author zhiwei_yang
 * @time 2020-3-23-16:48
 */
@FeignClient("nacos-service-registry")
public interface FeignService {

    @GetMapping("/provider")
    public String comsume();
}
