package com.xm.netty_proxy_client.proxyHandler;

import com.xm.netty_proxy_client.manager.ClientProxyConnectManager;
import com.xm.netty_proxy_common.callback.ConnectCallBack;
import com.xm.netty_proxy_common.msg.ProxyMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

import static com.xm.netty_proxy_common.msg.ProxyMessageType.*;

@Slf4j
public class ClientReceiveMessageHandler extends SimpleChannelInboundHandler<ProxyMessage> {

    private final ConnectCallBack connectCallBack;
    private final Channel localChannel;
    private final String targetHost;
    private final int targetPort;

    public ClientReceiveMessageHandler(ConnectCallBack connectCallBack, Channel localChannel, String targetHost, int targetPort) {
        this.connectCallBack = connectCallBack;
        this.localChannel = localChannel;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ProxyMessage proxyMessage) {
        if (proxyMessage == null) return;
        Channel proxyChannel = ctx.channel();

        switch (proxyMessage.getType()) {
            case BUILD_CONNECT_SUCCESS:
                log.info("【代理通道】【目标->{}:{}】建立通道成功", targetHost, targetPort);
                connectCallBack.success(proxyChannel);
                break;
            case TRANSFER:
                handleTransferData(proxyChannel, proxyMessage);
                break;
            case SERVER_PROXY_TARGET_FAIL:
            case SERVER_PROXY_TARGET_CLOSE:
                localChannel.close();
                ClientProxyConnectManager.returnProxyConnect(proxyChannel);
                break;
            default:
                break;
        }
    }

    private void handleTransferData(Channel proxyChannel, ProxyMessage proxyMessage) {
        if (localChannel == null || !localChannel.isActive()) {
            ClientProxyConnectManager.returnProxyConnect(proxyChannel);
            return;
        }

        ByteBuf data = proxyMessage.getData();
        if (data == null) return;

        //增加引用计数，防止被 SimpleChannelInboundHandler 自动释放导致异步写失败
        data.retain();

        if (!localChannel.isWritable()) {
            proxyChannel.config().setAutoRead(false);
        }

        localChannel.writeAndFlush(data).addListener(future -> {
            if (future.isSuccess()) {
                if (localChannel.isWritable()) proxyChannel.config().setAutoRead(true);
            } else {
                localChannel.close();
                ClientProxyConnectManager.returnProxyConnect(proxyChannel);
            }
        });
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("【代理通道】错误: {}", cause.getMessage());
        ClientProxyConnectManager.returnProxyConnect(ctx.channel());
        if (localChannel != null) localChannel.close();
    }
}