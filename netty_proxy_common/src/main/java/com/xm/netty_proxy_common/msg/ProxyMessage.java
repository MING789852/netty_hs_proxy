package com.xm.netty_proxy_common.msg;

import lombok.Data;

@Data
public class ProxyMessage {
    /**
     * 0x1、连接  0x2、断开
     */
    private byte type;

    /**
     * 目标服务器
     */
    private String targetHost;
    private int targetPort;

    /**
     * 账号密码
     */
    private String username;
    private String password;

    /**
     * 转发信息
     */
    private byte[] data;
}
