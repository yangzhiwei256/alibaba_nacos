package com.alibaba.nacos.config.server.service.dump;

import com.alibaba.nacos.config.server.manager.AbstractTask;
import com.alibaba.nacos.config.server.manager.TaskProcessor;
import com.alibaba.nacos.config.server.model.Page;
import com.alibaba.nacos.config.server.model.mapper.ConfigInfoBetaWrapper;
import com.alibaba.nacos.config.server.service.ConfigService;
import com.alibaba.nacos.config.server.service.PersistService;
import com.alibaba.nacos.config.server.utils.GroupKey2;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DumpAllBetaProcessor implements TaskProcessor {

    static final int PAGE_SIZE = 1000;
    final DumpService dumpService;
    final PersistService persistService;

    DumpAllBetaProcessor(DumpService dumpService) {
        this.dumpService = dumpService;
        this.persistService = dumpService.persistService;
    }

    @Override
    public boolean process(String taskType, AbstractTask task) {
        int rowCount = persistService.configInfoBetaCount();
        int pageCount = (int)Math.ceil(rowCount * 1.0 / PAGE_SIZE);

        int actualRowCount = 0;
        for (int pageNo = 1; pageNo <= pageCount; pageNo++) {
            Page<ConfigInfoBetaWrapper> page = persistService.findAllConfigInfoBetaForDumpAll(pageNo, PAGE_SIZE);
            if (page != null) {
                for (ConfigInfoBetaWrapper cf : page.getPageItems()) {
                    boolean result = ConfigService.dumpBeta(cf.getDataId(), cf.getGroup(), cf.getTenant(),
                        cf.getContent(), cf.getLastModified(), cf.getBetaIps());
                    log.info("[dump-all-beta-ok] result={}, {}, {}, length={}, md5={}", result,
                        GroupKey2.getKey(cf.getDataId(), cf.getGroup()), cf.getLastModified(), cf.getContent()
                            .length(), cf.getMd5());
                }

                actualRowCount += page.getPageItems().size();
                log.info("[all-dump-beta] {} / {}", actualRowCount, rowCount);
            }
        }
        return true;
    }
}