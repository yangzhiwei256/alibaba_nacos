package com.alibaba.nacos.config.server.service.dump;

import com.alibaba.nacos.config.server.manager.AbstractTask;
import com.alibaba.nacos.config.server.manager.TaskProcessor;
import com.alibaba.nacos.config.server.model.ConfigInfoWrapper;
import com.alibaba.nacos.config.server.model.Page;
import com.alibaba.nacos.config.server.service.*;
import com.alibaba.nacos.config.server.utils.GroupKey2;
import com.alibaba.nacos.config.server.utils.MD5;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class DumpAllProcessor implements TaskProcessor {

    DumpAllProcessor(DumpService dumpService) {
        this.dumpService = dumpService;
        this.persistService = dumpService.persistService;
    }

    @Override
    public boolean process(String taskType, AbstractTask task) {
        long currentMaxId = persistService.findConfigMaxId();
        long lastMaxId = 0;
        while (lastMaxId < currentMaxId) {
            Page<ConfigInfoWrapper> page = persistService.findAllConfigInfoFragment(lastMaxId,
                PAGE_SIZE);
            if (page != null && page.getPageItems() != null && !page.getPageItems().isEmpty()) {
                for (ConfigInfoWrapper configInfoWrapper : page.getPageItems()) {
                    long id = configInfoWrapper.getId();
                    lastMaxId = id > lastMaxId ? id : lastMaxId;
                    if (configInfoWrapper.getDataId().equals(AggrWhitelist.AGGRIDS_METADATA)) {
                        AggrWhitelist.load(configInfoWrapper.getContent());
                    }

                    if (configInfoWrapper.getDataId().equals(ClientIpWhiteList.CLIENT_IP_WHITELIST_METADATA)) {
                        ClientIpWhiteList.load(configInfoWrapper.getContent());
                    }

                    if (configInfoWrapper.getDataId().equals(SwitchService.SWITCH_META_DATAID)) {
                        SwitchService.load(configInfoWrapper.getContent());
                    }

                    boolean result = ConfigService.dump(configInfoWrapper.getDataId(), configInfoWrapper.getGroup(), configInfoWrapper.getTenant(), configInfoWrapper.getContent(),
                        configInfoWrapper.getLastModified(), configInfoWrapper.getType());

                    final String content = configInfoWrapper.getContent();
                    final String md5 = MD5.getInstance().getMD5String(content);
                    log.info("[dump-all-ok] {}, {}, length={}, md5={}",
                        GroupKey2.getKey(configInfoWrapper.getDataId(), configInfoWrapper.getGroup()), configInfoWrapper.getLastModified(), content.length(), md5);
                }
                log.info("[all-dump] {} / {}", lastMaxId, currentMaxId);
            } else {
                lastMaxId += PAGE_SIZE;
            }
        }
        return true;
    }

    static final int PAGE_SIZE = 1000;

    final DumpService dumpService;
    final PersistService persistService;
}
