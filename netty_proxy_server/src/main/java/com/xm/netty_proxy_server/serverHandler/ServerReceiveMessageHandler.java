package com.xm.netty_proxy_server.serverHandler;

import com.xm.netty_proxy_common.callback.ConnectCallBack;
import com.xm.netty_proxy_common.key.Constants;
import com.xm.netty_proxy_common.msg.ProxyMessage;
import com.xm.netty_proxy_server.config.ServerConfig;
import com.xm.netty_proxy_server.manager.ServerProxyConnectManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import lombok.extern.slf4j.Slf4j;

import static com.xm.netty_proxy_common.msg.ProxyMessageType.*;

@Slf4j
public class ServerReceiveMessageHandler extends SimpleChannelInboundHandler<ProxyMessage> {

    private String targetHost;
    private Integer targetPort;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ProxyMessage proxyMessage) {
        if (!(ServerConfig.USERNAME.equals(proxyMessage.getUsername()) && ServerConfig.PASSWORD.equals(proxyMessage.getPassword()))) {
            ctx.close();
            return;
        }

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
    }

    private void handleTransfer(Channel serverChannel, ProxyMessage msg) {
        Channel targetChannel = serverChannel.attr(Constants.NEXT_CHANNEL).get();
        if (targetChannel != null && targetChannel.isActive()) {
            ByteBuf payload = msg.getData();
            if (payload == null) return;

            //增加引用计数，确保异步写入期间内存不被回收
            payload.retain();

            if (!targetChannel.isWritable()) {
                serverChannel.config().setAutoRead(false);
            }

            targetChannel.writeAndFlush(payload).addListener(future -> {
                if (future.isSuccess()) {
                    if (targetChannel.isWritable()) serverChannel.config().setAutoRead(true);
                } else {
                    targetChannel.close();
                    serverChannel.close();
                }
            });
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

    private void safeCloseTarget(Channel serverChannel) {
        Channel targetChannel = serverChannel.attr(Constants.NEXT_CHANNEL).getAndSet(null);
        if (targetChannel != null && targetChannel.isActive()) {
            targetChannel.close();
        }
    }
}