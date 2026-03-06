package com.xm.netty_proxy_common.msg;

public class ProxyMessageType {

    public static final byte HEART_BEAT = 0x00;

    /**
     * 建立连接
     */
    public static final byte BUILD_CONNECT = 0x01;

    public static final byte BUILD_CONNECT_SUCCESS= 0x02;

    /**
     * 传输数据
     */
    public static final byte TRANSFER = 0x04;
    /**
     * 服务端代理连接目标失败
     */
    public static final byte SERVER_PROXY_TARGET_FAIL = 0X05;

    /**
     * 服务端代理连接目标关闭
     */
    public static final byte SERVER_PROXY_TARGET_CLOSE = 0X06;

    /**
     * 客户端通知服务端关闭连接
     */
    public static final byte CLIENT_NOTIFY_SERVER_CLOSE = 0X08;
}
