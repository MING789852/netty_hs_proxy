package com.xm.netty_proxy_client.proxyHandler;

import com.xm.netty_proxy_client.manager.ProxyConnectManager;
import com.xm.netty_proxy_common.callback.ConnectCallBack;
import com.xm.netty_proxy_common.msg.ProxyMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ReceiveProxyMessageHandler extends SimpleChannelInboundHandler<ProxyMessage> {

    private final ConnectCallBack connectCallBack;

    private final Channel localChannel;

    private final boolean isPoolChannel;

    public ReceiveProxyMessageHandler(boolean isPoolChannel, ConnectCallBack connectCallBack, Channel localChannel) {
        this.connectCallBack = connectCallBack;
        this.localChannel = localChannel;
        this.isPoolChannel = isPoolChannel;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext channelHandlerContext, ProxyMessage proxyMessage) {
        Channel proxyChannel=channelHandlerContext.channel();
        if (ProxyMessage.CONNECT_SUCCESS==proxyMessage.getType()){
            connectCallBack.success(proxyChannel,isPoolChannel);
        }else if (ProxyMessage.TRANSFER==proxyMessage.getType()){
            ByteBuf byteBuf = channelHandlerContext.alloc().buffer(proxyMessage.getData().length);
            log.info("[代理客户端]开始回写数据到本地,目标->{}",localChannel.remoteAddress());
            byteBuf.writeBytes(proxyMessage.getData());
            localChannel.writeAndFlush(byteBuf);
        }else if (ProxyMessage.CLOSE==proxyMessage.getType()){
            log.info("[代理客户端]接收到代理服务器关闭通知,代理目标地址->{}:{}",proxyMessage.getTargetHost(),proxyMessage.getTargetPort());
            //关闭本地连接
            localChannel.close();
            //归还代理连接
            ProxyConnectManager.returnProxyConnect(proxyChannel);
        }else {
            log.info("[代理客户端]接收到代理服务器异常操作类型");
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        log.info("[代理客户端]代理连接关闭");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("[代理客户端]错误",cause);
    }
}