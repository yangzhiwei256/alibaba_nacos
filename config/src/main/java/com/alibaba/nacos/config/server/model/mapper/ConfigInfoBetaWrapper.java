package com.alibaba.nacos.config.server.model.mapper;

import com.alibaba.nacos.config.server.model.ConfigInfo4Beta;

public class ConfigInfoBetaWrapper extends ConfigInfo4Beta {
        private static final long serialVersionUID = 4511997359365712505L;

        private long lastModified;

        public ConfigInfoBetaWrapper() {
        }

        public long getLastModified() {
            return lastModified;
        }

        public void setLastModified(long lastModified) {
            this.lastModified = lastModified;
        }

        @Override
        public int hashCode() {
            return super.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            return super.equals(obj);
        }
    }