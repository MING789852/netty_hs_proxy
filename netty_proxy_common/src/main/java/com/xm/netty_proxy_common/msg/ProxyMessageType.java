package com.xm.netty_proxy_common.msg;

public class ProxyMessageType {
    /**
     * 建立连接
     */
    public static final byte BUILD_CONNECT = 0x01;

    public static final byte CONNECT_SUCCESS= 0x02;

    /**
     * 传输数据
     */
    public static final byte TRANSFER = 0x04;
    /**
     * 服务端连接目标失败
     */
    public static final byte SERVER_PROXY_FAIL = 0X05;

    /**
     * 服务端代理关闭连接
     */
    public static final byte SERVER_PROXY_CLOSE = 0X06;

    /**
     * 通知服务端关闭连接
     */
    public static final byte NOTIFY_SERVER_CLOSE= 0X08;

    /**
     * 服务端关闭连接确认
     */
    public static final byte NOTIFY_SERVER_CLOSE_ACK=0X09;

}
