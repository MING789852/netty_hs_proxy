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

@Slf4j
public class HttpServerProxyClientHandler extends SimpleChannelInboundHandler<ByteBuf> {

    // 目标服务器信息
    private final String targetHost;
    private final int targetPort;
    private final String httpMethod;
    private final boolean isConnectMethod;
    private  ByteBuf firstByteBuf;

    public HttpServerProxyClientHandler(String targetHost, int targetPort, String httpMethod) {
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.httpMethod = httpMethod;
        this.isConnectMethod = "CONNECT".equalsIgnoreCase(httpMethod);
    }


    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf byteBuf) {
        firstByteBuf = byteBuf.retain();
        // 建立代理通道
        processHttpRequest(ctx);
    }

    /**
     * 处理HTTP请求
     */
    private void processHttpRequest(ChannelHandlerContext ctx) {
        Channel localChannel = ctx.channel();
        try {
            // 1. 创建代理请求
            ProxyRequest proxyRequest = new ProxyRequest();
            proxyRequest.setTargetHost(targetHost);
            proxyRequest.setTargetPort(targetPort);

            // 2. 获取代理连接
            ProxyConnectManager.getProxyConnect(new ConnectCallBack() {
                @Override
                public void success(Channel proxyServerChannel) {
                    if (!localChannel.isActive()) {
                        log.warn("【HTTP快速代理】【目标->{}:{}】本地通道已关闭，放弃建立代理", targetHost, targetPort);
                        return;
                    }
                    try {
                        handleProxyConnected(ctx, proxyServerChannel);
                    } catch (Exception e) {
                        log.error("【HTTP快速代理】【目标->{}:{}】处理代理连接成功时发生异常", targetHost, targetPort, e);
                        sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Internal Server Error");
                    }
                }

                @Override
                public void error(Channel proxyServerChannel) {
                    log.error("【HTTP快速代理】【目标->{}:{}】连接代理服务器失败", targetHost, targetPort);
                    sendErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY, "Proxy Server Unavailable");
                }
            }, ctx.channel(), proxyRequest);

        } catch (Exception e) {
            log.error("【HTTP快速代理】【目标->{}:{}】处理HTTP请求时发生异常", targetHost, targetPort, e);
            sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Internal Server Error");
        }
    }

    /**
     * 处理代理连接建立成功
     */
    private void handleProxyConnected(ChannelHandlerContext ctx, Channel proxyChannel) {
        Channel localChannel = ctx.channel();
        //区分处理
        //CONNECT方法用于建立HTTPS隧道，是SSL/TLS加密通信的基础
        if (isConnectMethod) {
            // CONNECT 方法：建立HTTPS/SSL隧道，代理不解析后续数据，仅作TCP层转发
            handleConnectMethod(ctx, localChannel, proxyChannel);
        } else {
            // 普通HTTP方法（GET/POST等）：代理解析并转发完整的HTTP请求
            handleHttpMethod(ctx, localChannel, proxyChannel);
        }
    }

    /**
     * 处理CONNECT方法
     */
    private void handleConnectMethod(ChannelHandlerContext ctx, Channel localChannel, Channel proxyChannel) {
        log.info("【HTTP快速代理】【目标->{}:{}】处理HTTPS隧道(CONNECT)", targetHost, targetPort);
        // 发送200 Connection Established响应
        String response = "HTTP/1.1 200 Connection Established\r\n\r\n";
        ByteBuf responseBuf = localChannel.alloc().buffer(response.length());
        responseBuf.writeBytes(response.getBytes(StandardCharsets.US_ASCII));
        localChannel.writeAndFlush(responseBuf).addListener(future -> {
            if (!future.isSuccess()) {
                log.error("【HTTP快速代理】【目标->{}:{}】发送200响应失败", targetHost, targetPort, future.cause());
                safeCloseResources(localChannel);
            }else {
                // 切换到代理模式
                switchToProxyMode(ctx,localChannel,proxyChannel);
            }
        });
    }

    /**
     * 处理普通HTTP方法
     */
    private void handleHttpMethod(ChannelHandlerContext ctx,Channel localChannel, Channel proxyChannel) {
        log.info("【HTTP快速代理】【目标->{}:{}】处理普通HTTP请求: {}", targetHost, targetPort, httpMethod);
        // 切换到代理模式
        switchToProxyMode(ctx,localChannel,proxyChannel);
    }


    /**
     * 切换到代理模式
     */
    private void  switchToProxyMode(ChannelHandlerContext ctx,Channel localChannel,Channel proxyChannel) {
        try {
            // 1. 移除当前处理器
            ctx.pipeline().remove(this);

            // 2. 添加代理消息发送处理器
            ctx.pipeline().addLast(Config.SEND_PROXY_MESSAGE_HANDLER,
                    new SendProxyMessageHandler(proxyChannel, targetHost, targetPort));

            // 3、传送第一个请求
            if (!isConnectMethod){
                proxyChannel.writeAndFlush(ProxyConnectManager.getProxyMessageManager().wrapTransferByteBuf(firstByteBuf)).addListener(future -> {
                    if (!future.isSuccess()) {
                        log.error("【HTTP快速代理】【目标->{}:{}】发送第一个请求失败", targetHost, targetPort, future.cause());
                        safeCloseResources(localChannel);
                    }else {
                        firstByteBuf.release();
                    }
                });
            }

            // 4、恢复自动读取
            ctx.channel().config().setAutoRead(true);
            log.info("【HTTP快速代理】【目标->{}:{}】已切换到代理模式",targetHost,targetPort);
        } catch (Exception e) {
            log.error("【HTTP快速代理】【目标->{}:{}】切换到代理模式失败", targetHost, targetPort, e);
            safeCloseResources(localChannel);
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
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        super.exceptionCaught(ctx, cause);
        safeCloseResources(ctx.channel());
    }

    /**
     * 安全关闭资源
     */
    private void safeCloseResources(Channel localChannel) {
        if (localChannel != null && localChannel.isActive()) {
            localChannel.close();
        }
        if (firstByteBuf!=null&&firstByteBuf.refCnt()>0){
            firstByteBuf.release();
        }
    }
}