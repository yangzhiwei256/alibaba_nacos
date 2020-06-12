package com.alibaba.nacos;

import com.alibaba.fastjson.JSON;
import com.alibaba.nacos.client.naming.utils.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author zhiwei_yang
 * @time 2020-3-23-16:21
 */
@RestController
public class ServiceProviderController {

    @Autowired
    private DiscoveryClient discoveryClient;

    @GetMapping("/provider")
    public String provider(){
        return "This is Service Provider";
    }

    @GetMapping("/service")
    public String service(){
        List<String> serviceIds = discoveryClient.getServices();
        if(CollectionUtils.isEmpty(serviceIds)){
            return StringUtils.EMPTY;
        }
        Map<String, List<ServiceInstance>> serviceInstanceMap = new HashMap<>(serviceIds.size());
        serviceIds.forEach(serviceId -> serviceInstanceMap.putIfAbsent(serviceId, discoveryClient.getInstances(serviceId))
        );
        return JSON.toJSONString(serviceInstanceMap);
    }
}
