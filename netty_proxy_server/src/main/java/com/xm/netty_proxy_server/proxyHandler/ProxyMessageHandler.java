package com.xm.netty_proxy_server.proxyHandler;

import com.xm.netty_proxy_common.key.Constants;
import com.xm.netty_proxy_server.manager.ProxyConnectManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ProxyMessageHandler extends SimpleChannelInboundHandler<ByteBuf> {

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        Channel connectChannel = ctx.channel();
        //初始关闭自动读取，等待客户端建立连接成功
        connectChannel.config().setAutoRead(false);
        log.info("【代理目标通道】通道已激活,暂不读取数据: {}", connectChannel.remoteAddress());
        super.channelActive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf byteBuf) {
        Channel connectChannel = ctx.channel();
        Channel serverChannel = connectChannel.attr(Constants.NEXT_CHANNEL).get();

        if (serverChannel != null && serverChannel.isActive()) {
            // 发送到代理管道
            serverChannel.writeAndFlush(
                    ProxyConnectManager.getProxyMessageManager().wrapTransferByteBuf(byteBuf)
            ).addListener(future -> {
                if (!future.isSuccess()) {
                    log.error("【代理目标通道】【目标 -> {}】回写数据失败", connectChannel.remoteAddress(), future.cause());
                }
            });
        } else {
            log.error("【代理目标通道】【目标 -> {}】回写数据失败,代理服务连接不存在或已关闭", connectChannel.remoteAddress());
            safeCloseResources(connectChannel);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        Channel connectChannel = ctx.channel();
        log.info("【代理目标通道】【目标 -> {}】关闭", connectChannel.remoteAddress());
        safeCloseResources(connectChannel);
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Channel connectChannel = ctx.channel();
        log.error("【代理目标通道】【目标 -> {}】异常", connectChannel.remoteAddress(), cause);
        safeCloseResources(connectChannel);
    }


    private void safeCloseResources(Channel connectChannel) {
        connectChannel.flush().close();
        Channel serverChannel = connectChannel.attr(Constants.NEXT_CHANNEL).get();
        if (serverChannel != null && serverChannel.isActive()) {
            // 通知代理客户端，目标通道已关闭
//            serverChannel.writeAndFlush(
//                    ProxyConnectManager.getProxyMessageManager().wrapServerProxyTargetClose()
//            ).addListener(future -> {
//                if (!future.isSuccess()) {
//                    log.info("【代理目标通道】【目标 -> {}】目标通道已关闭通知代理客户端失败", connectChannel.remoteAddress(), future.cause());
//                }
//            });
            //解绑服务端通道
            ProxyConnectManager.unbindChannel(serverChannel);
        }
    }
}
