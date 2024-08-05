package com.xm.netty_proxy_client.proxyHandler;

import com.xm.netty_proxy_client.manager.ProxyConnectManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SendProxyMessageHandler extends ChannelInboundHandlerAdapter {

    private final Channel proxyChannel;

    public SendProxyMessageHandler(Channel proxyChannel) {
        this.proxyChannel = proxyChannel;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (proxyChannel!=null){
            ByteBuf byteBuf= (ByteBuf) msg;
            log.info("[本地连接]{}发送数据到代理服务器->{}",ctx.channel().remoteAddress(),proxyChannel.remoteAddress());
            proxyChannel.writeAndFlush(ProxyConnectManager.wrapTransferByteBuf(byteBuf));
        } else {
            log.info("[本地连接]{}未关联代理服务器连接",ctx.channel().remoteAddress());
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        log.info("[本地连接]{}执行关闭操作",ctx.channel().remoteAddress());
        //发送客户端断开连接
        proxyChannel.writeAndFlush(ProxyConnectManager.wrapClose("4",4));
        //归还代理连接
        ProxyConnectManager.returnProxyConnect(proxyChannel);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("[本地连接]{}错误->{}",ctx.channel().remoteAddress(),cause.getMessage());
    }
}
