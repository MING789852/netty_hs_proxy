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
import java.util.ArrayDeque;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class HttpServerProxyClientHandler extends ChannelInboundHandlerAdapter {

    private final String targetHost;
    private final int targetPort;
    private final String httpMethod;
    private final boolean isConnectMethod;
    private final Queue<ByteBuf> bufferCache = new ArrayDeque<>();

    private Channel proxyChannel;

    // 守卫1：确保连接逻辑只触发一次 (CAS 保证)
    private final AtomicBoolean connectStarted = new AtomicBoolean(false);
    // 守卫2：确保 Pipeline 切换和缓存冲刷只执行一次
    private final AtomicBoolean switched = new AtomicBoolean(false);

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

        // 如果已经完成切换，直接向后传递（此为双重保险）
        if (switched.get()) {
            ctx.fireChannelRead(data);
            return;
        }

        // 1. 将数据加入缓存队列
        bufferCache.add(data);

        // 2. 利用 AtomicBoolean 保证 startProxyConnect 的唯一调用
        if (connectStarted.compareAndSet(false, true)) {
            // 暂停自动读取，防止在连接建立期间缓存过多数据导致 OOM
            ctx.channel().config().setAutoRead(false);
            startProxyConnect(ctx);
        }
    }

    private void startProxyConnect(ChannelHandlerContext ctx) {
        ProxyRequest request = new ProxyRequest();
        request.setTargetHost(targetHost);
        request.setTargetPort(targetPort);

        ClientProxyConnectManager.getProxyConnect(new ConnectCallBack() {
            @Override
            public void success(Channel pChannel) {
                // 回到 EventLoop 线程执行，确保线程安全
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
            // HTTPS 代理需要先回复 200 Connection Established
            String resp = "HTTP/1.1 200 Connection Established\r\n\r\n";
            ctx.writeAndFlush(Unpooled.copiedBuffer(resp, StandardCharsets.US_ASCII))
                    .addListener((ChannelFutureListener) f -> {
                        if (f.isSuccess()) {
                            // 弹出并释放 CONNECT 请求本身的 ByteBuf
                            ByteBuf first = bufferCache.poll();
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
        // 使用 switched 保证整个“切换”动作（改Pipeline、刷缓存、删自己）全局只执行一次
        if (switched.compareAndSet(false, true)) {
            ChannelPipeline pipeline = ctx.pipeline();

            // 1、添加 Handler
            if (pipeline.get(ClientConfig.SEND_PROXY_MESSAGE_HANDLER) == null) {
                pipeline.addLast(ClientConfig.SEND_PROXY_MESSAGE_HANDLER,
                        new ClientSendMessageHandler(proxyChannel, targetHost, targetPort));
            }

            try {
                // 2. 将缓存中的后续数据包全部冲刷到新添加的 Handler
                while (!bufferCache.isEmpty()) {
                    ByteBuf buf = bufferCache.poll();
                    if (buf != null) {
                        ctx.fireChannelRead(buf);
                    }
                }
            } finally {
                // 3. 严格清理
                cleanCache();
                ctx.channel().config().setAutoRead(true);

                // 4. 任务完成，将自己从 Pipeline 中移除
                if (pipeline.context(this) != null) {
                    pipeline.remove(this);
                }
                log.debug("【HTTP代理】模式切换成功: {}:{}", targetHost, targetPort);
            }
        }
    }

    private void cleanCache() {
        while (!bufferCache.isEmpty()) {
            ByteBuf b = bufferCache.poll();
            if (b != null && b.refCnt() > 0) {
                b.release();
            }
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
        log.error("【HTTP代理】Pipeline 异常: {}", cause.getMessage());
        cleanCache();
        ctx.close();
    }
}