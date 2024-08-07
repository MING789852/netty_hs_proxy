package com.xm.netty_proxy_common.msg;

import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;

public class ProxyMessageManager {

    private final String username;
    private final String password;

    public ProxyMessageManager(String username, String password) {
        this.username = username;
        this.password = password;
    }

    public ProxyMessage wrapPing(){
        ProxyMessage proxyMessage=new ProxyMessage();
        proxyMessage.setType(ProxyMessageType.PING);
        proxyMessage.setUsername(username);
        proxyMessage.setPassword(password);
        proxyMessage.setTargetHost("9");
        proxyMessage.setTargetPort(9);
        proxyMessage.setData("9".getBytes());
        return proxyMessage;
    }

    public ProxyMessage wrapServerProxyFail(String host, int port){
        ProxyMessage proxyMessage=new ProxyMessage();
        proxyMessage.setType(ProxyMessageType.SERVER_PROXY_FAIL);
        proxyMessage.setUsername(username);
        proxyMessage.setPassword(password);
        proxyMessage.setTargetHost(host);
        proxyMessage.setTargetPort(port);
        proxyMessage.setData("4".getBytes());

        return proxyMessage;
    }

    public ProxyMessage wrapNotifyServerCloseAck(){
        ProxyMessage proxyMessage=new ProxyMessage();
        proxyMessage.setType(ProxyMessageType.NOTIFY_SERVER_CLOSE_ACK);
        proxyMessage.setUsername(username);
        proxyMessage.setPassword(password);
        proxyMessage.setTargetHost("5");
        proxyMessage.setTargetPort(5);
        proxyMessage.setData("5".getBytes());
        return proxyMessage;
    }

    public ProxyMessage wrapConnectSuccess(String host,int port){
        ProxyMessage proxyMessage=new ProxyMessage();
        proxyMessage.setType(ProxyMessageType.CONNECT_SUCCESS);
        proxyMessage.setUsername(username);
        proxyMessage.setPassword(password);
        proxyMessage.setTargetHost(host);
        proxyMessage.setTargetPort(port);
        proxyMessage.setData("2".getBytes());

        return proxyMessage;
    }

    public ProxyMessage wrapBuildConnect(String host, int port){
        ProxyMessage proxyMessage=new ProxyMessage();
        proxyMessage.setType(ProxyMessageType.BUILD_CONNECT);
        proxyMessage.setUsername(username);
        proxyMessage.setPassword(password);
        proxyMessage.setTargetHost(host);
        proxyMessage.setTargetPort(port);
        proxyMessage.setData("1".getBytes());
        return proxyMessage;
    }

    public ProxyMessage wrapTransferByteBuf(ByteBuf byteBuf){
        int readableBytes= byteBuf.readableBytes();
        if (readableBytes==0){
            throw new RuntimeException("转发数据为null");
        }
        ProxyMessage proxyMessage=new ProxyMessage();
        proxyMessage.setType(ProxyMessageType.TRANSFER);
        proxyMessage.setUsername(username);
        proxyMessage.setPassword(password);
        proxyMessage.setTargetHost("0");
        proxyMessage.setTargetPort(0);
        byte[] data=new byte[readableBytes];
        byteBuf.readBytes(data);
        ReferenceCountUtil.release(byteBuf);
        proxyMessage.setData(data);
        return proxyMessage;
    }


    public ProxyMessage wrapNotifyServerClose(){
        ProxyMessage proxyMessage=new ProxyMessage();
        proxyMessage.setType(ProxyMessageType.NOTIFY_SERVER_CLOSE);
        proxyMessage.setUsername(username);
        proxyMessage.setPassword(password);
        proxyMessage.setTargetHost("4");
        proxyMessage.setTargetPort(4);
        proxyMessage.setData("4".getBytes());

        return proxyMessage;
    }
}
