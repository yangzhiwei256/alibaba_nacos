package com.alibaba.nacos;

import com.alibaba.fastjson.JSON;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务实例
 * @author zhiwei_yang
 * @time 2020-3-23-17:01
 */
@RestController
public class ServiceInstanceController {

    @Autowired
    private DiscoveryClient discoveryClient;

    @GetMapping("/serviceInstance")
    public String getServiceInstans(){
        List<String> serivceNameList = discoveryClient.getServices();
        if(CollectionUtils.isEmpty(serivceNameList)){
            return "empty";
        }
       Map<String, List<ServiceInstance>> serviceInstanceMap = new HashMap<>(serivceNameList.size());
        for(String serviceName : serivceNameList){
            serviceInstanceMap.put(serviceName, discoveryClient.getInstances(serviceName));
        }
        return JSON.toJSONString(serviceInstanceMap);
    }
}
