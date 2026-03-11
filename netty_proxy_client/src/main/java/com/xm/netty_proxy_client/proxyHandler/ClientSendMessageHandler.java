package com.xm.netty_proxy_client.proxyHandler;

import com.xm.netty_proxy_client.manager.ClientProxyConnectManager;
import com.xm.netty_proxy_common.msg.ProxyMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ClientSendMessageHandler extends ChannelInboundHandlerAdapter {

    private final Channel proxyChannel;
    private final String targetHost;
    private final int targetPort;

    public ClientSendMessageHandler(Channel proxyChannel, String targetHost, int targetPort) {
        this.proxyChannel = proxyChannel;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof ByteBuf) {
            ByteBuf byteBuf = (ByteBuf) msg;
            if (proxyChannel != null && proxyChannel.isActive()) {
                ProxyMessage pMsg = ClientProxyConnectManager.getProxyMessageManager().wrapTransferByteBuf(byteBuf);
                proxyChannel.writeAndFlush(pMsg).addListener(f -> {
                    if (!f.isSuccess()) ctx.close();
                });

                // 背压控制：代理通道拥堵时，暂停读取本地浏览器/应用数据
                if (!proxyChannel.isWritable()) {
                    ctx.channel().config().setAutoRead(false);
                }
            } else {
                ReferenceCountUtil.release(byteBuf);
                ctx.close();
            }
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        // 修复死锁：当本地通道恢复可写时，唤醒被暂停的代理通道
        if (ctx.channel().isWritable() && proxyChannel != null && proxyChannel.isActive()) {
            proxyChannel.config().setAutoRead(true);
        }
        super.channelWritabilityChanged(ctx);
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