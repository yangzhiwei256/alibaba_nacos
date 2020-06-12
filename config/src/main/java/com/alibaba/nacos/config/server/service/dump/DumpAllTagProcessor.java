package com.alibaba.nacos.config.server.service.dump;

import com.alibaba.nacos.config.server.manager.AbstractTask;
import com.alibaba.nacos.config.server.manager.TaskProcessor;
import com.alibaba.nacos.config.server.model.Page;
import com.alibaba.nacos.config.server.model.mapper.ConfigInfoTagWrapper;
import com.alibaba.nacos.config.server.service.ConfigService;
import com.alibaba.nacos.config.server.service.PersistService;
import com.alibaba.nacos.config.server.utils.GroupKey2;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class DumpAllTagProcessor implements TaskProcessor {

    DumpAllTagProcessor(DumpService dumpService) {
        this.dumpService = dumpService;
        this.persistService = dumpService.persistService;
    }

    @Override
    public boolean process(String taskType, AbstractTask task) {
        int rowCount = persistService.configInfoTagCount();
        int pageCount = (int)Math.ceil(rowCount * 1.0 / PAGE_SIZE);

        int actualRowCount = 0;
        for (int pageNo = 1; pageNo <= pageCount; pageNo++) {
            Page<ConfigInfoTagWrapper> page = persistService.findAllConfigInfoTagForDumpAll(pageNo, PAGE_SIZE);
            if (page != null) {
                for (ConfigInfoTagWrapper cf : page.getPageItems()) {
                    boolean result = ConfigService.dumpTag(cf.getDataId(), cf.getGroup(), cf.getTenant(), cf.getTag(),
                        cf.getContent(), cf.getLastModified());
                    log.info("[dump-all-Tag-ok] result={}, {}, {}, length={}, md5={}", result,
                        GroupKey2.getKey(cf.getDataId(), cf.getGroup()), cf.getLastModified(), cf.getContent()
                            .length(), cf.getMd5());
                }

                actualRowCount += page.getPageItems().size();
                log.info("[all-dump-tag] {} / {}", actualRowCount, rowCount);
            }
        }
        return true;
    }

    static final int PAGE_SIZE = 1000;

    final DumpService dumpService;
    final PersistService persistService;
}