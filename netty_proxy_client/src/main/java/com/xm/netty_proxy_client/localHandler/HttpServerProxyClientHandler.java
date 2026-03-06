package com.xm.netty_proxy_client.localHandler;

import com.xm.netty_proxy_client.config.Config;
import com.xm.netty_proxy_client.manager.ProxyConnectManager;
import com.xm.netty_proxy_client.proxyHandler.SendProxyMessageHandler;
import com.xm.netty_proxy_common.callback.ConnectCallBack;
import com.xm.netty_proxy_common.msg.ProxyRequest;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.codec.http.HttpResponseStatus;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class HttpServerProxyClientHandler extends SimpleChannelInboundHandler<ByteBuf> {

    // 目标服务器信息
    private final String targetHost;
    private final int targetPort;
    private final String httpMethod;
    private final boolean isConnectMethod;
    //使用原子引用管理第一个数据包，防止多线程竞争
    private final AtomicReference<ByteBuf> firstByteBufRef = new AtomicReference<>();

    public HttpServerProxyClientHandler(String targetHost, int targetPort, String httpMethod) {
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.httpMethod = httpMethod;
        this.isConnectMethod = "CONNECT".equalsIgnoreCase(httpMethod);
    }


    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf byteBuf) {
        //使用CAS操作，确保只有第一个到达的ByteBuf被保留和处理
        if (firstByteBufRef.compareAndSet(null, byteBuf.retain())) {
            // 成功设置第一个buf，开始处理请求
            processHttpRequest(ctx);
        } else {
            // 已经有第一个buf了，这意味着在等待建立代理连接时收到了后续数据。
            // 这是一个错误状态，通常意味着协议错误或对端行为异常。安全关闭。
            log.error("【HTTP快速代理】【目标->{}:{}】在等待代理连接时收到额外数据，关闭连接。", targetHost, targetPort);
            safeCloseResources(ctx.channel(), null); // 此时proxyChannel为null
        }
    }

    /**
     * 处理HTTP请求
     */
    private void processHttpRequest(ChannelHandlerContext ctx) {
        // 1. 创建代理请求
        ProxyRequest proxyRequest = new ProxyRequest();
        proxyRequest.setTargetHost(targetHost);
        proxyRequest.setTargetPort(targetPort);
        // 2. 获取代理连接
        ProxyConnectManager.getProxyConnect(new ConnectCallBack() {
            @Override
            public void success(Channel proxyServerChannel) {
                //非eventLoop，排队等候
                ctx.channel().eventLoop().execute(() -> {
                    if (!ctx.channel().isActive()) {
                        log.warn("【HTTP快速代理】【目标->{}:{}】本地通道已关闭，放弃建立代理", targetHost, targetPort);
                        releaseFirstBuf();
                        ProxyConnectManager.returnProxyConnect(proxyServerChannel);
                        return;
                    }
                    handleProxyConnected(ctx, proxyServerChannel);
                });
            }

            @Override
            public void error(Channel proxyServerChannel) {
                //非eventLoop，排队等候
                ctx.channel().eventLoop().execute(() -> {
                    log.error("【HTTP快速代理】【目标->{}:{}】连接代理服务器失败", targetHost, targetPort);
                    sendErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY, "Proxy Server Unavailable");
                    releaseFirstBuf();
                    //归还代理连接
                    ProxyConnectManager.returnProxyConnect(proxyServerChannel);
                });
            }
        }, ctx.channel(), proxyRequest);
    }

    /**
     * 处理代理连接建立成功
     */
    private void handleProxyConnected(ChannelHandlerContext ctx, Channel proxyChannel) {
        Channel localChannel = ctx.channel();
        ByteBuf firstBuf = firstByteBufRef.get();
        if (firstBuf == null) {
            log.error("【HTTP快速代理】【目标->{}:{}】内部错误：首个数据包丢失", targetHost, targetPort);
            safeCloseResources(localChannel, proxyChannel); // 关闭本地通道并归还代理通道
            return;
        }

        if (isConnectMethod) {
            handleConnectMethod(ctx, localChannel, proxyChannel, firstBuf);
        } else {
            handleHttpMethod(ctx, localChannel, proxyChannel, firstBuf);
        }
    }

    /**
     * 处理CONNECT方法
     */
    private void handleConnectMethod(ChannelHandlerContext ctx, Channel localChannel, Channel proxyChannel, ByteBuf firstBuf) {
        log.info("【HTTP快速代理】【目标->{}:{}】处理HTTPS隧道(CONNECT)", targetHost, targetPort);
        //CONNECT方法也需要转发第一个数据包,可能包含TLS握手数据。
        // 1. 发送200 Connection Established响应
        String response = "HTTP/1.1 200 Connection Established\r\n\r\n";
        ByteBuf responseBuf = localChannel.alloc().buffer(response.length());
        responseBuf.writeBytes(response.getBytes(StandardCharsets.US_ASCII));
        localChannel.writeAndFlush(responseBuf).addListener(future -> {
            if (!future.isSuccess()) {
                log.error("【HTTP快速代理】【目标->{}:{}】发送200响应失败", targetHost, targetPort, future.cause());
                safeCloseResources(localChannel, proxyChannel); // 关闭本地通道并归还代理通道
                releaseFirstBuf();
            } else {
                // 2. 切换到代理模式，并发送首个数据包
                switchToProxyMode(ctx, localChannel, proxyChannel, firstBuf, true);
            }
        });
    }

    /**
     * 处理普通HTTP方法
     */
    private void handleHttpMethod(ChannelHandlerContext ctx, Channel localChannel, Channel proxyChannel, ByteBuf firstBuf) {
        log.info("【HTTP快速代理】【目标->{}:{}】处理普通HTTP请求: {}", targetHost, targetPort, httpMethod);
        // 直接切换到代理模式并发送数据
        switchToProxyMode(ctx, localChannel, proxyChannel, firstBuf, false);
    }


    /**
     * 切换到代理模式
     */
    private void  switchToProxyMode(ChannelHandlerContext ctx, Channel localChannel, Channel proxyChannel, ByteBuf firstBuf, boolean isConnectMethod) {
        try {
            // 1. 移除当前处理器
            ctx.pipeline().remove(this);

            // 2. 添加代理消息发送处理器
            ctx.pipeline().addLast(Config.SEND_PROXY_MESSAGE_HANDLER,
                    new SendProxyMessageHandler(proxyChannel, targetHost, targetPort));

            // 3、传送第一个请求
            if (!isConnectMethod){
                proxyChannel.writeAndFlush(ProxyConnectManager.getProxyMessageManager().wrapTransferByteBuf(firstBuf)).addListener(future -> {
                    //非eventLoop，排队等候
                    ctx.channel().eventLoop().execute(() -> {
                        if (!future.isSuccess()) {
                            log.error("【HTTP快速代理】【目标->{}:{}】发送第一个请求失败", targetHost, targetPort, future.cause());
                            // 修复：发送失败时，需要安全关闭本地通道，并归还代理通道
                            safeCloseResources(localChannel, proxyChannel);
                        } else {
                            // 发送成功，只需释放缓存
                            firstBuf.release();
                            firstByteBufRef.set(null);
                        }
                    });
                });
            }
            // 恢复自动读取
            ctx.channel().config().setAutoRead(true);
            log.info("【HTTP快速代理】【目标->{}:{}】恢复自动读取", targetHost, targetPort);
            log.info("【HTTP快速代理】【目标->{}:{}】已切换到代理模式",targetHost,targetPort);
        } catch (Exception e) {
            log.error("【HTTP快速代理】【目标->{}:{}】切换到代理模式失败", targetHost, targetPort, e);
            firstBuf.release();
            firstByteBufRef.set(null);
            safeCloseResources(localChannel, proxyChannel); // 关闭本地通道并归还代理通道
        }
    }

    /**
     * 发送错误响应
     */
    private void sendErrorResponse(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        try {
            // 构造HTTP响应头
            String responseHeader = "HTTP/1.1 " + status.code() + " " + status.reasonPhrase() + "\r\n" +
                    "Content-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Length: " + message.getBytes(StandardCharsets.UTF_8).length + "\r\n" +
                    "Connection: close\r\n" +
                    "\r\n";

            // 创建ByteBuf并写入响应头和消息体
            ByteBuf responseBuf = ctx.alloc().buffer(
                    responseHeader.length() + message.getBytes(StandardCharsets.UTF_8).length
            );
            responseBuf.writeBytes(responseHeader.getBytes(StandardCharsets.US_ASCII));
            responseBuf.writeBytes(message.getBytes(StandardCharsets.UTF_8));

            // 发送并关闭连接
            ctx.writeAndFlush(responseBuf).addListener(ChannelFutureListener.CLOSE);
        } catch (Exception e) {
            log.error("【HTTP快速代理】【目标->{}:{}】发送错误响应失败", targetHost, targetPort, e);
            ctx.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("【HTTP快速代理】【目标->{}:{}】发生异常", targetHost, targetPort, cause);
        // 异常时没有关联的proxyChannel，所以传入null
        safeCloseResources(ctx.channel(), null);
    }

    /**
     * 安全关闭资源
     * @param localChannel 本地通道
     * @param proxyChannel 代理通道（可能为null）
     */
    private void safeCloseResources(Channel localChannel, Channel proxyChannel) {
        releaseFirstBuf();
        if (localChannel != null && localChannel.isActive()) {
            localChannel.close();
        }
        ProxyConnectManager.returnProxyConnect(proxyChannel);
    }

    /**
     * 释放缓存的第一个ByteBuf
     */
    private void releaseFirstBuf() {
        ByteBuf buf = firstByteBufRef.getAndSet(null);
        if (buf != null && buf.refCnt() > 0) {
            buf.release();
        }
    }
}