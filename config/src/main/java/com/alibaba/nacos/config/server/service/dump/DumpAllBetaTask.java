package com.alibaba.nacos.config.server.service.dump;

import com.alibaba.nacos.config.server.manager.AbstractTask;

class DumpAllBetaTask extends AbstractTask {
    @Override
    public void merge(AbstractTask task) {
    }

    static final String TASK_ID = "dumpAllBetaConfigTask";
}