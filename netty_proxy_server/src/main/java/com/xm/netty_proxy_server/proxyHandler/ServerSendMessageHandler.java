package com.xm.netty_proxy_server.proxyHandler;

import com.xm.netty_proxy_server.manager.ServerProxyConnectManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ServerSendMessageHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private final Channel serverChannel; // 指向 Proxy Client 的连接

    public ServerSendMessageHandler(Channel serverChannel) {
        this.serverChannel = serverChannel;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // 初始关闭自动读取，等待授权完成及 BuildConnectSuccess 发送后再开启
        ctx.channel().config().setAutoRead(false);
        super.channelActive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf byteBuf) {
        if (serverChannel != null && serverChannel.isActive()) {
            // 流控：如果代理通道发送缓冲区满了，停止读取目标服务器数据
            if (!serverChannel.isWritable()) {
                ctx.channel().config().setAutoRead(false);
            }

            serverChannel.writeAndFlush(ServerProxyConnectManager.getProxyMessageManager().wrapTransferByteBuf(byteBuf))
                    .addListener(future -> {
                        if (future.isSuccess()) {
                            // 发送成功后，如果通道恢复可写，重新开启读取
                            if (serverChannel.isWritable()) {
                                ctx.channel().config().setAutoRead(true);
                            }
                        } else {
                            log.error("【目标 -> 代理】转发数据失败, 关闭连接");
                            ctx.close();
                        }
                    });
        } else {
            ctx.close();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.info("【代理目标通道】目标服务器主动断开: {}", ctx.channel().remoteAddress());
        // 通知客户端：目标服务器已关闭
        if (serverChannel != null && serverChannel.isActive()) {
            serverChannel.writeAndFlush(ServerProxyConnectManager.getProxyMessageManager().wrapServerProxyTargetClose());
        }
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("【代理目标通道】异常: {}", cause.getMessage());
        ctx.close();
    }
}