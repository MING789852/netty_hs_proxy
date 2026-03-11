package com.xm.netty_proxy_server.proxyHandler;

import com.xm.netty_proxy_server.manager.ServerProxyConnectManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ServerSendMessageHandler extends ChannelInboundHandlerAdapter {

    private final Channel serverChannel; // 连向代理客户端的通道

    public ServerSendMessageHandler(Channel serverChannel) {
        this.serverChannel = serverChannel;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        ctx.channel().config().setAutoRead(true);
        super.channelActive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof ByteBuf) {
            ByteBuf byteBuf = (ByteBuf) msg;
            if (serverChannel != null && serverChannel.isActive()) {
                // 直接封装转发
                serverChannel.writeAndFlush(ServerProxyConnectManager.getProxyMessageManager().wrapTransferByteBuf(byteBuf))
                        .addListener(future -> {
                            if (!future.isSuccess()) {
                                log.error("【转发失败】目标 -> 客户端");
                                ctx.close();
                            }
                        });

                // 背压控制：客户端连接发送缓冲区满时，暂停读取目标服务器数据
                if (!serverChannel.isWritable()) {
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
        // 修复死锁：当目标服务器连接恢复可写时，唤醒对客户端连接的读取
        if (ctx.channel().isWritable() && serverChannel != null && serverChannel.isActive()) {
            serverChannel.config().setAutoRead(true);
        }
        super.channelWritabilityChanged(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (serverChannel != null && serverChannel.isActive()) {
            serverChannel.writeAndFlush(ServerProxyConnectManager.getProxyMessageManager().wrapServerProxyTargetClose());
        }
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }
}