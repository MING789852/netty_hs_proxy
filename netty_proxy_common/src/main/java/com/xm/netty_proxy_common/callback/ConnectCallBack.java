package com.xm.netty_proxy_common.callback;


import io.netty.channel.Channel;

public interface ConnectCallBack {
    void success(Channel channel,boolean isPoolChannel);
    void error(Channel channel);
}
