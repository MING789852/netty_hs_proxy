package com.xm.netty_proxy_client.proxyHandler;

import com.xm.netty_proxy_client.manager.ProxyConnectManager;
import com.xm.netty_proxy_common.callback.ConnectCallBack;
import com.xm.netty_proxy_common.msg.ProxyMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;

import static com.xm.netty_proxy_common.msg.ProxyMessageType.*;

@Slf4j
public class ReceiveProxyMessageHandler extends SimpleChannelInboundHandler<ProxyMessage> {

    private final ConnectCallBack connectCallBack;

    private final Channel localChannel;

    private final String targetHost;

    private final int targetPort;

    private final AtomicBoolean processed = new AtomicBoolean(false);

    public ReceiveProxyMessageHandler(ConnectCallBack connectCallBack, Channel localChannel, String targetHost, int targetPort) {
        this.connectCallBack = connectCallBack;
        this.localChannel = localChannel;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext channelHandlerContext, ProxyMessage proxyMessage) {
        if (proxyMessage == null) {
            log.warn("【代理通道】【目标->{}:{}】接收到空消息", targetHost, targetPort);
            return;
        }
        Channel proxyChannel = channelHandlerContext.channel();
        byte type = proxyMessage.getType();

        switch (type) {
            // 建立连接成功
            case BUILD_CONNECT_SUCCESS:
                handleBuildConnectSuccess(proxyChannel);
                break;
            // 传输数据
            case TRANSFER:
                handleTransferData(proxyChannel,proxyMessage);
                break;
            //服务端代理连接目标失败
            case SERVER_PROXY_TARGET_FAIL:
                handleServerProxyTargetFail(proxyChannel);
                break;
            //服务端代理连接目标关闭
            case SERVER_PROXY_TARGET_CLOSE:
                handleServerProxyTargetClose(proxyChannel);
                break;
            default:
                log.warn("【代理通道】【目标->{}:{}】接收到未知消息类型", targetHost, targetPort);
                break;
        }
    }

    /**
     * 处理连接成功
     */
    private void handleBuildConnectSuccess(Channel proxyChannel) {
        if (processed.compareAndSet(false, true)) {
            log.info("【代理通道】【目标->{}:{}】建立通道成功", targetHost, targetPort);
            connectCallBack.success(proxyChannel);
        } else {
            log.warn("【代理通道】【目标->{}:{}】重复接收到连接成功响应, 已忽略", targetHost, targetPort);
        }
    }

    /**
     * 处理数据传输
     */
    private void handleTransferData(Channel proxyChannel,ProxyMessage proxyMessage) {
        if (localChannel.isActive()) {
            byte[] data = proxyMessage.getData();
            ByteBuf byteBuf = Unpooled.wrappedBuffer(data);
            localChannel.writeAndFlush(byteBuf).addListener(future -> {
                if (!future.isSuccess()) {
                    log.error("【代理通道】【目标->{}:{}】回写数据到本地失败", targetHost, targetPort);
                    safeCloseResources(proxyChannel);
                }
            });
        } else {
            log.warn("【代理通道】【目标->{}:{}】本地通道不活跃", targetHost, targetPort);
            safeCloseResources(proxyChannel);
        }
    }

    /**
     * 处理服务端代理连接目标失败
     */
    private void handleServerProxyTargetFail(Channel proxyChannel) {
        log.info("【代理通道】【目标->{}:{}】因服务端代理目标连接失败,故关闭本地通道", targetHost, targetPort);
        safeCloseResources(proxyChannel);
    }

    /**
     * 处理服务端代理连接目标关闭
     */
    private void handleServerProxyTargetClose(Channel proxyChannel) {
        log.info("【代理通道】【目标->{}:{}】因服务端代理目标连接已关闭,故关闭本地通道", targetHost, targetPort);
        safeCloseResources(proxyChannel);
    }


    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("【代理通道】【目标->{}:{}】关闭", targetHost,targetPort);
        // 如果连接成功回调还没有被调用，需要通知失败
        if (!processed.get() && connectCallBack != null) {
            connectCallBack.error(ctx.channel());
        }
        safeCloseResources(ctx.channel());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("【代理通道】【目标->{}:{}】错误->{}", targetHost,targetPort, cause.getMessage());
        // 如果连接成功回调还没有被调用，需要通知失败
        if (!processed.get() && connectCallBack != null) {
            connectCallBack.error(ctx.channel());
        }
        safeCloseResources(ctx.channel());
        ctx.close();
    }

    /**
     * 安全关闭资源
     */
    private void safeCloseResources(Channel proxyChannel) {
        try {
            // 关闭本地通道
            if (localChannel != null && localChannel.isActive()) {
                localChannel.flush().close();
            }
            // 归还代理连接
            ProxyConnectManager.returnProxyConnect(proxyChannel);
        } catch (Exception e) {
            log.error("【代理通道】【目标->{}:{}】关闭资源时发生异常", targetHost, targetPort, e);
        }
    }
}
