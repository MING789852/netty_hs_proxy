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
        ctx.channel().config().setAutoRead(false);
        super.channelActive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf byteBuf) throws Exception {
        Channel connectChannel=ctx.channel();
        Channel serverChannel=connectChannel.attr(Constants.NEXT_CHANNEL).get();
        //回写数据
        if (serverChannel!=null&&serverChannel.isActive()){
            log.debug("[代理目标连接]目标地址->{},回写数据",connectChannel.remoteAddress());
            byteBuf.retain();
            serverChannel.writeAndFlush(ProxyConnectManager.getProxyMessageManager().wrapTransferByteBuf(byteBuf));
        }else {
            connectChannel.flush().close().sync();
            String msg;
            if (serverChannel==null){
                msg="代理服务连接不存在";
            }else {
                msg="代理服务连接已断开";
            }
            log.error("[代理目标连接]目标地址->{},{}，无法回写",connectChannel.remoteAddress(),msg);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        Channel connectChannel=ctx.channel();
        Channel serverChannel=connectChannel.attr(Constants.NEXT_CHANNEL).get();
        if (serverChannel!=null&&serverChannel.isActive()){
            serverChannel.writeAndFlush(ProxyConnectManager.getProxyMessageManager().wrapServerProxyClose());
            ProxyConnectManager.unBindChannel(serverChannel);
        }
        log.info("[代理目标连接]目标地址->{},连接关闭",ctx.channel().remoteAddress());
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("[代理目标连接]目标地址->{},错误->{}",ctx.channel().remoteAddress(),cause.getMessage());
    }
}
