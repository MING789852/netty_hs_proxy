package com.xm.netty_proxy_server.proxyHandler;

import com.xm.netty_proxy_common.key.Constants;
import com.xm.netty_proxy_server.manager.ProxyConnectManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ProxyMessageHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        Channel connectChannel=ctx.channel();
        Channel serverChannel=connectChannel.attr(Constants.NEXT_CHANNEL).get();
        //回写数据
        if (serverChannel!=null){
            ByteBuf byteBuf= (ByteBuf) msg;
            log.debug("[代理目标连接]目标地址->{},回写数据",connectChannel.remoteAddress());
            serverChannel.writeAndFlush(ProxyConnectManager.getProxyMessageManager().wrapTransferByteBuf(byteBuf));
        }else {
            log.debug("[代理目标连接]目标地址->{},代理服务连接不存在，无法回写",connectChannel.remoteAddress());
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        log.info("[代理目标连接]目标地址->{},连接关闭",ctx.channel().remoteAddress());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("[代理目标连接]目标地址->{},错误->{}",ctx.channel().remoteAddress(),cause.getMessage());
    }
}
