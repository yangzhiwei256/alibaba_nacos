package com.alibaba.nacos.common.utils;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.net.telnet.TelnetClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * @author zhiwei_yang
 * @time 2020-7-21-16:25
 */
public final class NetUtils {

    private static String LOCAL_IP;

    public static String localIP() {
        try {
            if (!StringUtils.isEmpty(LOCAL_IP)) {
                return LOCAL_IP;
            }

            String ip = System.getProperty("com.alibaba.nacos.client.naming.local.ip", InetAddress.getLocalHost().getHostAddress());

            return LOCAL_IP = ip;
        } catch (UnknownHostException e) {
            return "resolve_failed";
        }
    }

    /**
     * 判断服务器端口是否有效
     * @param host
     * @param port
     * @return
     */
    public static boolean isPortUseful(String host, Integer port, int timeOut){
        if(StringUtils.isEmpty(host) || null == port){
            return false;
        }
        TelnetClient client = new TelnetClient();
        client.setConnectTimeout(timeOut);
        try {
            client.connect(host, port);
            return true;
        } catch (IOException e) {
            return false;
        }finally {
            try {
                if(client.isConnected()) {
                    client.disconnect();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
