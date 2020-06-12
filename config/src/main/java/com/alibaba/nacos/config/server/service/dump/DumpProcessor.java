package com.alibaba.nacos.config.server.service.dump;

import com.alibaba.nacos.config.server.manager.AbstractTask;
import com.alibaba.nacos.config.server.manager.TaskProcessor;
import com.alibaba.nacos.config.server.model.ConfigInfo;
import com.alibaba.nacos.config.server.model.ConfigInfo4Beta;
import com.alibaba.nacos.config.server.model.ConfigInfo4Tag;
import com.alibaba.nacos.config.server.service.AggrWhitelist;
import com.alibaba.nacos.config.server.service.ClientIpWhiteList;
import com.alibaba.nacos.config.server.service.ConfigService;
import com.alibaba.nacos.config.server.service.SwitchService;
import com.alibaba.nacos.config.server.service.trace.ConfigTraceService;
import com.alibaba.nacos.config.server.utils.GroupKey2;
import org.apache.commons.lang3.StringUtils;

class DumpProcessor implements TaskProcessor {

    DumpProcessor(DumpService dumpService) {
        this.dumpService = dumpService;
    }

    @Override
    public boolean process(String taskType, AbstractTask task) {
        DumpTask dumpTask = (DumpTask)task;
        String[] pair = GroupKey2.parseKey(dumpTask.groupKey);
        String dataId = pair[0];
        String group = pair[1];
        String tenant = pair[2];
        long lastModified = dumpTask.lastModified;
        String handleIp = dumpTask.handleIp;
        boolean isBeta = dumpTask.isBeta;
        String tag = dumpTask.tag;
        if (isBeta) {
            // beta发布，则dump数据，更新beta缓存
            ConfigInfo4Beta cf = dumpService.persistService.findConfigInfo4Beta(dataId, group, tenant);
            boolean result;
            if (null != cf) {
                result = ConfigService.dumpBeta(dataId, group, tenant, cf.getContent(), lastModified, cf.getBetaIps());
                if (result) {
                    ConfigTraceService.logDumpEvent(dataId, group, tenant, null, lastModified, handleIp,
                        ConfigTraceService.DUMP_EVENT_OK, System.currentTimeMillis() - lastModified,
                        cf.getContent().length());
                }
            } else {
                result = ConfigService.removeBeta(dataId, group, tenant);
                if (result) {
                    ConfigTraceService.logDumpEvent(dataId, group, tenant, null, lastModified, handleIp,
                        ConfigTraceService.DUMP_EVENT_REMOVE_OK, System.currentTimeMillis() - lastModified, 0);
                }
            }
            return result;
        } else {
            if (StringUtils.isBlank(tag)) {
                ConfigInfo cf = dumpService.persistService.findConfigInfo(dataId, group, tenant);
                if (dataId.equals(AggrWhitelist.AGGRIDS_METADATA)) {
                    if (null != cf) {
                        AggrWhitelist.load(cf.getContent());
                    } else {
                        AggrWhitelist.load(null);
                    }
                }

                if (dataId.equals(ClientIpWhiteList.CLIENT_IP_WHITELIST_METADATA)) {
                    if (null != cf) {
                        ClientIpWhiteList.load(cf.getContent());
                    } else {
                        ClientIpWhiteList.load(null);
                    }
                }

                if (dataId.equals(SwitchService.SWITCH_META_DATAID)) {
                    if (null != cf) {
                        SwitchService.load(cf.getContent());
                    } else {
                        SwitchService.load(null);
                    }
                }

                boolean result;
                if (null != cf) {
                    result = ConfigService.dump(dataId, group, tenant, cf.getContent(), lastModified, cf.getType());

                    if (result) {
                        ConfigTraceService.logDumpEvent(dataId, group, tenant, null, lastModified, handleIp,
                            ConfigTraceService.DUMP_EVENT_OK, System.currentTimeMillis() - lastModified,
                            cf.getContent().length());
                    }
                } else {
                    result = ConfigService.remove(dataId, group, tenant);

                    if (result) {
                        ConfigTraceService.logDumpEvent(dataId, group, tenant, null, lastModified, handleIp,
                            ConfigTraceService.DUMP_EVENT_REMOVE_OK, System.currentTimeMillis() - lastModified, 0);
                    }
                }
                return result;
            } else {
                ConfigInfo4Tag cf = dumpService.persistService.findConfigInfo4Tag(dataId, group, tenant, tag);
                //
                boolean result;
                if (null != cf) {
                    result = ConfigService.dumpTag(dataId, group, tenant, tag, cf.getContent(), lastModified);
                    if (result) {
                        ConfigTraceService.logDumpEvent(dataId, group, tenant, null, lastModified, handleIp,
                            ConfigTraceService.DUMP_EVENT_OK, System.currentTimeMillis() - lastModified,
                            cf.getContent().length());
                    }
                } else {
                    result = ConfigService.removeTag(dataId, group, tenant, tag);
                    if (result) {
                        ConfigTraceService.logDumpEvent(dataId, group, tenant, null, lastModified, handleIp,
                            ConfigTraceService.DUMP_EVENT_REMOVE_OK, System.currentTimeMillis() - lastModified, 0);
                    }
                }
                return result;
            }
        }

    }

    final DumpService dumpService;
}