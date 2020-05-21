package com.alibaba.nacos;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author zhiwei_yang
 * @time 2020-3-23-16:21
 */
@RestController
public class ServiceProviderController {

    @GetMapping("/provider")
    public String provider(){
        return "This is Service Provider";
    }
}
