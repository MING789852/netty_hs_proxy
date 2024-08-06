package com.xm.netty_proxy_common.msg;

public class ProxyMessage {
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
     * 通知客户端关闭连接
     */
    public static final byte SERVER_PROXY_FAIL = 0X05;

    /**
     * 通知服务端关闭连接
     */
    public static final byte NOTIFY_SERVER_CLOSE= 0X06;

    /**
     * 服务端关闭连接确认
     */
    public static final byte NOTIFY_SERVER_CLOSE_ACK=0X07;

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

    public String getTargetHost() {
        return targetHost;
    }

    public void setTargetHost(String targetHost) {
        this.targetHost = targetHost;
    }

    public int getTargetPort() {
        return targetPort;
    }

    public void setTargetPort(int targetPort) {
        this.targetPort = targetPort;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public byte getType() {
        return type;
    }

    public void setType(byte type) {
        this.type = type;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }
}
