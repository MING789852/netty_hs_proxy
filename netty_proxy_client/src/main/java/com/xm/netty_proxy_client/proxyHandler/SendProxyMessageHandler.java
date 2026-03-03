package com.xm.netty_proxy_client.proxyHandler;

import com.xm.netty_proxy_client.manager.ProxyConnectManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SendProxyMessageHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private final Channel proxyChannel;

    private final String targetHost;

    private final int targetPort;

    public SendProxyMessageHandler(Channel proxyChannel, String targetHost, int targetPort) {
        this.proxyChannel = proxyChannel;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
    }



    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf byteBuf) {
        if (proxyChannel!=null&& proxyChannel.isActive()){
            proxyChannel.writeAndFlush(ProxyConnectManager.getProxyMessageManager().wrapTransferByteBuf(byteBuf))
                    .addListener(future -> {
                        if (!future.isSuccess()){
                            log.error("【本地通道】【目标->{}:{}】发送数据到代理服务器失败",targetHost,targetPort);
                        }
                    });
        } else {
            log.error("【本地通道】【目标->{}:{}】未关联代理服务器连接或未连接",targetHost,targetPort);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        safeCloseResources(ctx.channel());
        log.info("【本地通道】【目标->{}:{}】关闭",targetHost,targetPort);
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        safeCloseResources(ctx.channel());
        log.error("【本地通道】【目标->{}:{}】错误->{}",targetHost,targetPort,cause.getMessage());
    }


    private void safeCloseResources(Channel localChannel) {
        try {
            // 关闭本地通道
            if (localChannel != null && localChannel.isActive()) {
                localChannel.flush().close();
            }
            //发送客户端断开连接
            proxyChannel.writeAndFlush(ProxyConnectManager.getProxyMessageManager().wrapClientNotifyServerClose()).addListener(future -> {
                // 归还代理连接
                ProxyConnectManager.returnProxyConnect(proxyChannel);
            });
        } catch (Exception e) {
            log.error("【本地通道】【目标->{}:{}】关闭资源时发生异常", targetHost, targetPort, e);
        }
    }
}
