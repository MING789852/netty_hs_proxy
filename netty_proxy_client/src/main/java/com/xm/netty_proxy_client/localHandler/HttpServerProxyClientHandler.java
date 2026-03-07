package com.xm.netty_proxy_client.localHandler;

import com.xm.netty_proxy_client.config.ClientConfig;
import com.xm.netty_proxy_client.manager.ClientProxyConnectManager;
import com.xm.netty_proxy_client.proxyHandler.ClientSendMessageHandler;
import com.xm.netty_proxy_common.callback.ConnectCallBack;
import com.xm.netty_proxy_common.msg.ProxyRequest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.HttpResponseStatus;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.Queue;

@Slf4j
public class HttpServerProxyClientHandler extends ChannelInboundHandlerAdapter {

    private final String targetHost;
    private final int targetPort;
    private final String httpMethod;
    private final boolean isConnectMethod;
    private final Queue<ByteBuf> bufferCache = new LinkedList<>();
    private Channel proxyChannel;
    private boolean connecting = false;

    public HttpServerProxyClientHandler(String targetHost, int targetPort, String httpMethod) {
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.httpMethod = httpMethod;
        this.isConnectMethod = "CONNECT".equalsIgnoreCase(httpMethod);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ByteBuf)) {
            ctx.fireChannelRead(msg);
            return;
        }

        ByteBuf data = (ByteBuf) msg;
        if (proxyChannel != null && proxyChannel.isActive()) {
            ctx.fireChannelRead(data);
        } else {
            bufferCache.add(data);
            if (!connecting) {
                connecting = true;
                ctx.channel().config().setAutoRead(false);
                startProxyConnect(ctx);
            }
        }
    }

    private void startProxyConnect(ChannelHandlerContext ctx) {
        ProxyRequest request = new ProxyRequest();
        request.setTargetHost(targetHost);
        request.setTargetPort(targetPort);

        ClientProxyConnectManager.getProxyConnect(new ConnectCallBack() {
            @Override
            public void success(Channel pChannel) {
                ctx.executor().execute(() -> {
                    proxyChannel = pChannel;
                    handleConnectionReady(ctx);
                });
            }

            @Override
            public void error(Channel pChannel) {
                ctx.executor().execute(() -> {
                    sendErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY, "Proxy Connect Fail");
                    cleanCache();
                });
            }
        }, ctx.channel(), request);
    }

    private void handleConnectionReady(ChannelHandlerContext ctx) {
        if (isConnectMethod) {
            String resp = "HTTP/1.1 200 Connection Established\r\n\r\n";
            ctx.writeAndFlush(Unpooled.copiedBuffer(resp, StandardCharsets.US_ASCII))
                    .addListener(f -> {
                        if (f.isSuccess()) {
                            ByteBuf first = bufferCache.poll(); // 移除并释放 CONNECT 请求包
                            if (first != null) first.release();
                            completeSwitch(ctx);
                        } else {
                            ctx.close();
                        }
                    });
        } else {
            completeSwitch(ctx);
        }
    }

    private void completeSwitch(ChannelHandlerContext ctx) {
        // 1. 添加正式转发器
        ctx.pipeline().addLast(ClientConfig.SEND_PROXY_MESSAGE_HANDLER,
                new ClientSendMessageHandler(proxyChannel, targetHost, targetPort));

        try {
            // 2. 冲刷缓存至下一个 Handler
            while (!bufferCache.isEmpty()) {
                ByteBuf buf = bufferCache.poll();
                if (buf != null) ctx.fireChannelRead(buf);
            }
        } finally {
            // 3. 严格清理：防止异常导致的内存泄漏
            cleanCache();
            ctx.pipeline().remove(this);
            ctx.channel().config().setAutoRead(true);
            log.debug("【HTTP代理】切换至转发模式完毕: {}:{}", targetHost, targetPort);
        }
    }

    private void cleanCache() {
        while (!bufferCache.isEmpty()) {
            ByteBuf b = bufferCache.poll();
            if (b != null && b.refCnt() > 0) b.release();
        }
    }

    private void sendErrorResponse(ChannelHandlerContext ctx, HttpResponseStatus status, String msg) {
        String resp = "HTTP/1.1 " + status.code() + " " + status.reasonPhrase() + "\r\n" +
                "Content-Length: " + msg.length() + "\r\n\r\n" + msg;
        ctx.writeAndFlush(Unpooled.copiedBuffer(resp, StandardCharsets.UTF_8))
                .addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("【HTTP代理】异常: {}", cause.getMessage());
        cleanCache();
        ctx.close();
    }
}