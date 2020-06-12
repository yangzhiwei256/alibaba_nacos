package com.alibaba.nacos.config.server.service.dump;

import com.alibaba.nacos.config.server.manager.AbstractTask;
import com.alibaba.nacos.config.server.manager.TaskProcessor;
import com.alibaba.nacos.config.server.model.ConfigInfo;
import com.alibaba.nacos.config.server.model.ConfigInfoWrapper;
import com.alibaba.nacos.config.server.service.ConfigService;
import com.alibaba.nacos.config.server.service.PersistService;
import com.alibaba.nacos.config.server.utils.GroupKey2;
import com.alibaba.nacos.config.server.utils.MD5;
import lombok.extern.slf4j.Slf4j;

import java.sql.Timestamp;
import java.util.List;

@Slf4j
class DumpChangeProcessor implements TaskProcessor {

    final DumpService dumpService;
    final PersistService persistService;
    final Timestamp startTime;
    final Timestamp endTime;

    DumpChangeProcessor(DumpService dumpService, Timestamp startTime, Timestamp endTime) {
        this.dumpService = dumpService;
        this.persistService = dumpService.persistService;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    @Override
    public boolean process(String taskType, AbstractTask task) {
        log.warn("quick start; startTime:{},endTime:{}",
            startTime, endTime);
        log.warn("updateMd5 start");
        long startUpdateMd5 = System.currentTimeMillis();
        List<ConfigInfoWrapper> updateMd5List = persistService.listAllGroupKeyMd5();
        log.warn("updateMd5 count:{}", updateMd5List.size());
        for (ConfigInfoWrapper config : updateMd5List) {
            final String groupKey = GroupKey2.getKey(config.getDataId(), config.getGroup());
            ConfigService.updateMd5(groupKey, config.getMd5(), config.getLastModified());
        }
        long endUpdateMd5 = System.currentTimeMillis();
        log.warn("updateMd5 done,cost:{}", endUpdateMd5 - startUpdateMd5);

        log.warn("deletedConfig start");
        long startDeletedConfigTime = System.currentTimeMillis();
        List<ConfigInfo> configDeleted = persistService.findDeletedConfig(startTime, endTime);
        log.warn("deletedConfig count:{}", configDeleted.size());
        for (ConfigInfo configInfo : configDeleted) {
            if (persistService.findConfigInfo(configInfo.getDataId(), configInfo.getGroup(),
                configInfo.getTenant()) == null) {
                ConfigService.remove(configInfo.getDataId(), configInfo.getGroup(), configInfo.getTenant());
            }
        }
        long endDeletedConfigTime = System.currentTimeMillis();
        log.warn("deletedConfig done,cost:{}", endDeletedConfigTime - startDeletedConfigTime);

        log.warn("changeConfig start");
        long startChangeConfigTime = System.currentTimeMillis();
        List<ConfigInfoWrapper> changeConfigs = persistService.findChangeConfig(startTime, endTime);
        log.warn("changeConfig count:{}", changeConfigs.size());
        for (ConfigInfoWrapper cf : changeConfigs) {
            boolean result = ConfigService.dumpChange(cf.getDataId(), cf.getGroup(), cf.getTenant(), cf.getContent(), cf.getLastModified());
            final String content = cf.getContent();
            final String md5 = MD5.getInstance().getMD5String(content);
            log.info(
                "[dump-change-ok] {}, {}, length={}, md5={}",
                    GroupKey2.getKey(cf.getDataId(), cf.getGroup()),
                    cf.getLastModified(), content.length(), md5);
        }
        ConfigService.reloadConfig();
        long endChangeConfigTime = System.currentTimeMillis();
        log.warn("changeConfig done,cost:{}",endChangeConfigTime - startChangeConfigTime);
        return true;
    }
}