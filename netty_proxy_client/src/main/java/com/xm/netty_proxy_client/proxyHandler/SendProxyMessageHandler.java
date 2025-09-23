package com.xm.netty_proxy_client.proxyHandler;

import com.xm.netty_proxy_client.manager.ProxyConnectManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SendProxyMessageHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private final Channel proxyChannel;

    public SendProxyMessageHandler(Channel proxyChannel) {
        this.proxyChannel = proxyChannel;
    }


    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf byteBuf) throws Exception {
        if (proxyChannel!=null&& proxyChannel.isActive()){
            log.info("[本地连接]{}发送数据到代理服务器->{}",ctx.channel().remoteAddress(),proxyChannel.remoteAddress());
            byteBuf.retain();
            proxyChannel.writeAndFlush(ProxyConnectManager.getProxyMessageManager().wrapTransferByteBuf(byteBuf));
        } else {
            log.info("[本地连接]{}未关联代理服务器连接或未连接",ctx.channel().remoteAddress());
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.info("[本地连接]{}执行关闭操作",ctx.channel().remoteAddress());
        //发送客户端断开连接
        proxyChannel.flush();
        proxyChannel.writeAndFlush(ProxyConnectManager.getProxyMessageManager().wrapNotifyServerClose());
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("[本地连接]{}错误->{}",ctx.channel().remoteAddress(),cause.getMessage());
    }
}
