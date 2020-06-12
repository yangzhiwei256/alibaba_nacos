package com.alibaba.nacos.client.config.impl;

import com.alibaba.nacos.api.config.listener.ConfigListener;

class ManagerListenerWrap {
    final ConfigListener configListener;
    String lastCallMd5 = CacheData.getMd5String(null);
    String lastContent = null;

    ManagerListenerWrap(ConfigListener configListener) {
        this.configListener = configListener;
    }

    ManagerListenerWrap(ConfigListener configListener, String md5) {
        this.configListener = configListener;
        this.lastCallMd5 = md5;
    }

    ManagerListenerWrap(ConfigListener configListener, String md5, String lastContent) {
        this.configListener = configListener;
        this.lastCallMd5 = md5;
        this.lastContent = lastContent;
    }

    @Override
    public boolean equals(Object obj) {
        if (null == obj || obj.getClass() != getClass()) {
            return false;
        }
        if (obj == this) {
            return true;
        }
        ManagerListenerWrap other = (ManagerListenerWrap) obj;
        return configListener.equals(other.configListener);
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }

}
