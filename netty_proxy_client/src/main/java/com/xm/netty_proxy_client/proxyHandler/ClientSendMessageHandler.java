package com.xm.netty_proxy_client.proxyHandler;

import com.xm.netty_proxy_client.manager.ClientProxyConnectManager;
import com.xm.netty_proxy_common.msg.ProxyMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ClientSendMessageHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private final Channel proxyChannel;
    private final String targetHost;
    private final int targetPort;

    public ClientSendMessageHandler(Channel proxyChannel, String targetHost, int targetPort) {
        this.proxyChannel = proxyChannel;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf byteBuf) {
        if (proxyChannel != null && proxyChannel.isActive()) {
            // 直接传递 ByteBuf 包装
            ProxyMessage msg = ClientProxyConnectManager.getProxyMessageManager().wrapTransferByteBuf(byteBuf);
            proxyChannel.writeAndFlush(msg).addListener(f -> {
                if (!f.isSuccess()) ctx.close();
            });
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("【本地发送】本地连接已关闭，通知远程端");
        if (proxyChannel != null && proxyChannel.isActive()) {
            // 通知服务端：客户端已主动断开，服务端可以关闭对目标网站的连接了
            proxyChannel.writeAndFlush(ClientProxyConnectManager.getProxyMessageManager().wrapClientNotifyServerClose())
                    .addListener(f -> ClientProxyConnectManager.returnProxyConnect(proxyChannel));
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("【本地发送】异常: {}", cause.getMessage());
        ctx.close();
    }
}