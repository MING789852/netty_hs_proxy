package com.xm.netty_proxy_server.serverHandler;

import com.xm.netty_proxy_common.callback.ConnectCallBack;
import com.xm.netty_proxy_common.key.Constants;
import com.xm.netty_proxy_common.msg.ProxyMessage;
import com.xm.netty_proxy_server.config.Config;
import com.xm.netty_proxy_server.manager.ProxyConnectManager;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

import static com.xm.netty_proxy_common.msg.ProxyMessageType.*;

@Slf4j
public class ServerMessageHandler extends SimpleChannelInboundHandler<ProxyMessage> {

    private String targetHost;
    private Integer targetPort;

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ProxyMessage proxyMessage) {
        Channel serverChannel = ctx.channel();
        // 验证用户名密码
        if (!(Config.USERNAME.equals(proxyMessage.getUsername()) && Config.PASSWORD.equals(proxyMessage.getPassword()))) {
            log.error("【代理通道】【目标{}:{}】授权失败", proxyMessage.getTargetHost(), proxyMessage.getTargetPort());
            ctx.close();
            return;
        }
        byte type = proxyMessage.getType();
        // 处理不同类型的消息
        switch (type) {
            case BUILD_CONNECT:
                handleBuildConnect(serverChannel, proxyMessage);
                break;
            case TRANSFER:
                handleTransfer(serverChannel, proxyMessage);
                break;
            case CLIENT_NOTIFY_SERVER_CLOSE:
                handleClientNotifyServerClose(serverChannel);
                break;
            default:
                log.error("【代理通道】【目标{}:{}】未知消息类型: {}", proxyMessage.getTargetHost(), proxyMessage.getTargetPort(), type);
                break;
        }
    }


    /**
     * 处理建立连接请求
     */
    private void handleBuildConnect(Channel serverChannel, ProxyMessage proxyMessage) {
        this.targetHost = proxyMessage.getTargetHost();
        this.targetPort = proxyMessage.getTargetPort();
        ProxyConnectManager.connect(serverChannel, this.targetHost, this.targetPort, new ConnectCallBack() {
            @Override
            public void success(Channel connectChannel) {
                // 非serverChannel的eventLoop，排队等候
                serverChannel.eventLoop().execute(() -> {
                    // 发送连接成功响应
                    serverChannel.writeAndFlush(
                            ProxyConnectManager.getProxyMessageManager().wrapBuildConnectSuccess(targetHost, targetPort)
                    ).addListener(future -> {
                        if (!future.isSuccess()) {
                            log.error("【代理通道】【目标{}:{}】目标通道建立失败，发送响应失败", targetHost, targetPort);
                        } else {
                            serverChannel.attr(Constants.NEXT_CHANNEL).set(connectChannel);
                            connectChannel.config().setAutoRead(true);
                            log.info("【代理目标通道】【目标{}:{}】开始读取数据", targetHost, targetPort);
                        }
                    });
                });
            }

            @Override
            public void error(Channel connectChannel) {
                // 非serverChannel的eventLoop，排队等候
                serverChannel.eventLoop().execute(() -> {
                    // 通知代理客户端，代理服务器连接代理目标失败
                    serverChannel.writeAndFlush(ProxyConnectManager.getProxyMessageManager().wrapServerProxyTargetFail(targetHost, targetPort))
                            .addListener(future -> {
                                if (!future.isSuccess()) {
                                    log.error("【代理通道】【目标{}:{}】目标连接建立失败", targetHost, targetPort, future.cause());
                                }
                            });
                });
            }
        });
    }

    /**
     * 处理数据传输
     */
    private void handleTransfer(Channel serverChannel, ProxyMessage proxyMessage) {
        Channel connectChannel = serverChannel.attr(Constants.NEXT_CHANNEL).get();
        if (connectChannel != null && connectChannel.isActive()) {
            ByteBuf byteBuf = Unpooled.wrappedBuffer(proxyMessage.getData());
            connectChannel.writeAndFlush(byteBuf).addListener(future -> {
                if (!future.isSuccess()) {
                    log.error("【代理通道】【目标{}:{}】转发数据到目标通道失败", targetHost, targetPort, future.cause());
                }
            });
        } else {
            log.error("【代理通道】【目标{}:{}】无有效目标通道，无法转发数据", targetHost, targetPort);
        }
    }

    /**
     * 处理客户端关闭通知
     */
    private void handleClientNotifyServerClose(Channel serverChannel) {
        //安全关闭资源
        safeCloseResources(serverChannel);
        log.info("【代理通道】【目标{}:{}】接收到代理客户端断开连接请求", targetHost, targetPort);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        //安全关闭资源
        safeCloseResources(ctx.channel());
        log.info("【代理通道】【目标{}:{}】关闭", targetHost, targetPort);
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        //安全关闭资源
        safeCloseResources(ctx.channel());
        log.error("【代理通道】【目标{}:{}】异常", targetHost, targetPort, cause);
    }

    /**
     * 安全关闭资源
     */
    private void safeCloseResources(Channel serverChannel) {
        Channel connectProxyChannel = serverChannel.attr(Constants.NEXT_CHANNEL).get();
        if (connectProxyChannel != null) {
            connectProxyChannel.close();
            serverChannel.attr(Constants.NEXT_CHANNEL).set(null);
        }
    }
}