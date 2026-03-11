package com.xm.netty_proxy_server.serverHandler;

import com.xm.netty_proxy_common.callback.ConnectCallBack;
import com.xm.netty_proxy_common.key.Constants;
import com.xm.netty_proxy_common.msg.ProxyMessage;
import com.xm.netty_proxy_server.config.ServerConfig;
import com.xm.netty_proxy_server.manager.ServerProxyConnectManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import static com.xm.netty_proxy_common.msg.ProxyMessageType.*;

@Slf4j
public class ServerReceiveMessageHandler extends ChannelInboundHandlerAdapter {

    private String targetHost;
    private Integer targetPort;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ProxyMessage)) {
            ctx.fireChannelRead(msg);
            return;
        }

        ProxyMessage proxyMessage = (ProxyMessage) msg;

        if (!(ServerConfig.USERNAME.equals(proxyMessage.getUsername()) && ServerConfig.PASSWORD.equals(proxyMessage.getPassword()))) {
            if (proxyMessage.getData() != null) ReferenceCountUtil.release(proxyMessage.getData());
            ctx.close();
            return;
        }

        try {
            switch (proxyMessage.getType()) {
                case BUILD_CONNECT:
                    handleBuildConnect(ctx.channel(), proxyMessage);
                    break;
                case TRANSFER:
                    handleTransfer(ctx.channel(), proxyMessage);
                    break;
                case CLIENT_NOTIFY_SERVER_CLOSE:
                    safeCloseTarget(ctx.channel());
                    break;
                default:
                    break;
            }
        } finally {
            // 安全机制：确保非转发类消息的内存被释放
            if (proxyMessage.getType() != TRANSFER) {
                if (proxyMessage.getData() != null) {
                    ReferenceCountUtil.release(proxyMessage.getData());
                }
            }
        }
    }

    private void handleTransfer(Channel serverChannel, ProxyMessage msg) {
        Channel targetChannel = serverChannel.attr(Constants.NEXT_CHANNEL).get();
        if (targetChannel != null && targetChannel.isActive()) {
            ByteBuf payload = msg.getData();
            if (payload == null) return;

            // 取消旧代码的 retain()，直接交给 writeAndFlush 处理生命周期
            targetChannel.writeAndFlush(payload).addListener(future -> {
                if (!future.isSuccess()) {
                    targetChannel.close();
                    serverChannel.close();
                }
            });

            // 背压控制：目标服务器接收拥堵，暂停从客户端读取
            if (!targetChannel.isWritable()) {
                serverChannel.config().setAutoRead(false);
            }
        } else {
            // 如果目标通道已断开，丢弃数据防止内存泄漏
            if (msg.getData() != null) {
                ReferenceCountUtil.release(msg.getData());
            }
        }
    }

    private void handleBuildConnect(Channel serverChannel, ProxyMessage proxyMessage) {
        this.targetHost = proxyMessage.getTargetHost();
        this.targetPort = proxyMessage.getTargetPort();

        ServerProxyConnectManager.connect(serverChannel, targetHost, targetPort, new ConnectCallBack() {
            @Override
            public void success(Channel targetChannel) {
                serverChannel.eventLoop().execute(() -> {
                    serverChannel.writeAndFlush(ServerProxyConnectManager.getProxyMessageManager().wrapBuildConnectSuccess(targetHost, targetPort))
                            .addListener(future -> {
                                if (future.isSuccess()) {
                                    serverChannel.attr(Constants.NEXT_CHANNEL).set(targetChannel);
                                    targetChannel.config().setAutoRead(true);
                                } else {
                                    targetChannel.close();
                                }
                            });
                });
            }

            @Override
            public void error(Channel targetChannel) {
                serverChannel.writeAndFlush(ServerProxyConnectManager.getProxyMessageManager().wrapServerProxyTargetFail(targetHost, targetPort));
            }
        });
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        // 修复死锁：当客户端连接(serverChannel)恢复可写时，唤醒目标服务器连接(targetChannel)的读取
        Channel targetChannel = ctx.channel().attr(Constants.NEXT_CHANNEL).get();
        if (ctx.channel().isWritable() && targetChannel != null && targetChannel.isActive()) {
            targetChannel.config().setAutoRead(true);
        }
        super.channelWritabilityChanged(ctx);
    }

    private void safeCloseTarget(Channel serverChannel) {
        Channel targetChannel = serverChannel.attr(Constants.NEXT_CHANNEL).getAndSet(null);
        if (targetChannel != null && targetChannel.isActive()) {
            targetChannel.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("【服务端接收】异常: {}", cause.getMessage());
        safeCloseTarget(ctx.channel());
        ctx.close();
    }
}