## 配置中心客户端
nacos-cloud-config

---
## 服务注册客户端
nacos-cloud-registry


---
## 配置

默认配置：nacos-default.properties（nacos core模块）
- tomcat工作日志：server.tomcat.basedir, 默认 ${user.dir}/nacos/tomcat

---
### 环境变量配置
nacos.home 未指定默认配置
- nacos配置中心、服务注冊缓存目录：默认 ${user.dir}/nacos
- nacos工作日志：nacos.logs.path, 默认 ${user.dir}/nacos/logs
- cluster.conf：默认${user.dir}/nacos/conf

nacos.conf: cluster.conf 全路径

nacos.server.ip： 指定服务器实例IP,避免多网卡启动问题

nacos.logs.path： nacos 日志目录

授权：客户端需指定用户名/密码
- nacos.core.auth.enabled=true
- nacos.core.auth.system.type=nacos