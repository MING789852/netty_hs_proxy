package com.xm.netty_proxy_client.proxyHandler;

import com.xm.netty_proxy_client.manager.ProxyConnectManager;
import com.xm.netty_proxy_common.callback.ConnectCallBack;
import com.xm.netty_proxy_common.msg.ProxyMessage;
import com.xm.netty_proxy_common.msg.ProxyMessageType;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
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
        if (ProxyMessageType.CONNECT_SUCCESS==proxyMessage.getType()){
            connectCallBack.success(proxyChannel,isPoolChannel);
        }else if (ProxyMessageType.TRANSFER==proxyMessage.getType()){
            if (localChannel.isActive()){
                ByteBuf byteBuf = channelHandlerContext.alloc().buffer(proxyMessage.getData().length);
                log.info("[代理客户端]开始回写数据到本地,目标->{}",localChannel.remoteAddress());
                byteBuf.writeBytes(proxyMessage.getData());
                localChannel.writeAndFlush(byteBuf);
            }else {
                //归还代理连接
                ProxyConnectManager.returnProxyConnect(proxyChannel);
            }
        }else if (ProxyMessageType.SERVER_PROXY_FAIL==proxyMessage.getType()){
            log.info("[代理客户端]接收到代理服务器连接失败,关闭本地连接,归还代理连接\n{}:{}",proxyMessage.getTargetHost(),proxyMessage.getTargetPort());
            //关闭本地连接
            localChannel.flush().close();
            //归还代理连接
            ProxyConnectManager.returnProxyConnect(proxyChannel);
        }else if (ProxyMessageType.NOTIFY_SERVER_CLOSE_ACK==proxyMessage.getType()){
            log.info("[代理客户端]接收到代理服务器已关闭通知确认,归还代理连接");
            //归还代理连接
            ProxyConnectManager.returnProxyConnect(proxyChannel);
        }else {
            log.info("[代理客户端]接收到代理服务器异常操作类型");
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        Channel proxyChannel=ctx.channel();
        log.info("[代理客户端]代理连接关闭,归还代理连接");
        ProxyConnectManager.returnProxyConnect(proxyChannel);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("[代理客户端]错误",cause);
    }
}
