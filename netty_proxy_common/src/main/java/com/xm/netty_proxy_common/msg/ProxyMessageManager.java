package com.xm.netty_proxy_common.msg;

import io.netty.buffer.ByteBuf;

public class ProxyMessageManager {

    private final String username;
    private final String password;

    public ProxyMessageManager(String username, String password) {
        this.username = username;
        this.password = password;
    }

    public ProxyMessage wrapServerProxyTargetFail(String host, int port){
        ProxyMessage proxyMessage=new ProxyMessage();
        proxyMessage.setType(ProxyMessageType.SERVER_PROXY_TARGET_FAIL);
        proxyMessage.setUsername(username);
        proxyMessage.setPassword(password);
        proxyMessage.setTargetHost(host);
        proxyMessage.setTargetPort(port);
        proxyMessage.setData("4".getBytes());

        return proxyMessage;
    }


    public ProxyMessage wrapServerProxyTargetClose(){
        ProxyMessage proxyMessage=new ProxyMessage();
        proxyMessage.setType(ProxyMessageType.SERVER_PROXY_TARGET_CLOSE);
        proxyMessage.setUsername(username);
        proxyMessage.setPassword(password);
        proxyMessage.setTargetHost("8");
        proxyMessage.setTargetPort(8);
        proxyMessage.setData("8".getBytes());

        return proxyMessage;
    }

    public ProxyMessage wrapClientNotifyServerClose(){
        ProxyMessage proxyMessage=new ProxyMessage();
        proxyMessage.setType(ProxyMessageType.CLIENT_NOTIFY_SERVER_CLOSE);
        proxyMessage.setUsername(username);
        proxyMessage.setPassword(password);
        proxyMessage.setTargetHost("4");
        proxyMessage.setTargetPort(4);
        proxyMessage.setData("4".getBytes());

        return proxyMessage;
    }

    public ProxyMessage wrapBuildConnectSuccess(String host,int port){
        ProxyMessage proxyMessage=new ProxyMessage();
        proxyMessage.setType(ProxyMessageType.BUILD_CONNECT_SUCCESS);
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
        try {
            byteBuf.retain();
            int readableBytes= byteBuf.readableBytes();
            byte[] data;
            if (readableBytes==0){
                data=new byte[0];
            }else {
                data=new byte[readableBytes];
                byteBuf.readBytes(data);
            }
            ProxyMessage proxyMessage=new ProxyMessage();
            proxyMessage.setType(ProxyMessageType.TRANSFER);
            proxyMessage.setUsername(username);
            proxyMessage.setPassword(password);
            proxyMessage.setTargetHost("0");
            proxyMessage.setTargetPort(0);
            proxyMessage.setData(data);
            return proxyMessage;
        }finally {
            byteBuf.release();
        }
    }
}
