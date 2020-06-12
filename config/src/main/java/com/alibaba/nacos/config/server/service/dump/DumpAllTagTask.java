package com.alibaba.nacos.config.server.service.dump;

import com.alibaba.nacos.config.server.manager.AbstractTask;

class DumpAllTagTask extends AbstractTask {
    @Override
    public void merge(AbstractTask task) {
    }

    static final String TASK_ID = "dumpAllTagConfigTask";
}