package com.alibaba.nacos.config.server.service.notify;

import com.alibaba.nacos.config.server.constant.Constants;
import com.alibaba.nacos.config.server.utils.RunningConfigUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.text.MessageFormat;

/**
 * 配置更新客户端配置通知
 */
@Slf4j
public class NotifySingleTask extends NotifyTask {

        private final String target;
        public String url;

        /** 是否灰度发布 **/
        private final boolean isBeta;

        private static final String URL_PATTERN = "http://{0}{1}" + Constants.COMMUNICATION_CONTROLLER_PATH
            + "/dataChange"
            + "?dataId={2}&group={3}";
        private static final String URL_PATTERN_TENANT = "http://{0}{1}" + Constants.COMMUNICATION_CONTROLLER_PATH
            + "/dataChange" + "?dataId={2}&group={3}&tenant={4}";
        private int failCount;

        public NotifySingleTask(String dataId, String group, String tenant, long lastModified, String target) {
            this(dataId, group, tenant, lastModified, target, false);
        }

        public NotifySingleTask(String dataId, String group, String tenant, long lastModified, String target, boolean isBeta) {
            this(dataId, group, tenant, null, lastModified, target, isBeta);
        }

        public NotifySingleTask(String dataId, String group, String tenant, String tag, long lastModified,
                                String target, boolean isBeta) {
            super(dataId, group, tenant, lastModified);
            this.target = target;
            this.isBeta = isBeta;
            try {
                dataId = URLEncoder.encode(dataId, Constants.ENCODE);
                group = URLEncoder.encode(group, Constants.ENCODE);
            } catch (UnsupportedEncodingException e) {
                log.error("URLEncoder encode error", e);
            }
            if (StringUtils.isBlank(tenant)) {
                this.url = MessageFormat.format(URL_PATTERN, target, RunningConfigUtils.getContextPath(), dataId, group);
            } else {
                this.url = MessageFormat.format(URL_PATTERN_TENANT, target, RunningConfigUtils.getContextPath(), dataId, group, tenant);
            }
            if (StringUtils.isNotEmpty(tag)) {
                url = url + "&tag=" + tag;
            }
            failCount = 0;
            // this.executor = executor;
        }

        @Override
        public void setFailCount(int count) {
            this.failCount = count;
        }

        @Override
        public int getFailCount() {
            return failCount;
        }

        public String getTargetIP() {
            return target;
        }

    public String getTarget() {
        return target;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public boolean isBeta() {
        return isBeta;
    }
}