package com.xm.netty_proxy_server.manager;

import com.xm.netty_proxy_common.callback.ConnectCallBack;
import com.xm.netty_proxy_common.key.Constants;
import com.xm.netty_proxy_common.msg.ProxyMessageManager;
import com.xm.netty_proxy_server.config.Config;
import com.xm.netty_proxy_server.proxyHandler.ProxyMessageHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

@Slf4j
public class ProxyConnectManager {

    // 共享的EventLoopGroup
    private static final NioEventLoopGroup eventLoopGroup= new NioEventLoopGroup();
    private static final Bootstrap bootstrap;

    @Getter
    private static final ProxyMessageManager proxyMessageManager;

    // 连接超时配置
    private static final int CONNECT_TIMEOUT_MS = 5000;

    static {
        // 初始化代理消息管理器
        proxyMessageManager = new ProxyMessageManager(Config.USERNAME, Config.PASSWORD);

        // 初始化Bootstrap
        bootstrap = new Bootstrap()
                .group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel socketChannel) {
                        ChannelPipeline pipeline=socketChannel.pipeline();
                        pipeline.addLast(new ProxyMessageHandler());
                    }
                });
    }

    /**
     * 连接到目标服务器
     */
    public static void connect(String host, int port, ConnectCallBack connectCallBack) {
        if (host == null || host.trim().isEmpty() || port <= 0 || port > 65535) {
            log.error("【代理服务】【目标->{}:{}】无效的目标地址", host, port);
            connectCallBack.error(null);
            return;
        }
        bootstrap.connect(host, port).addListener((ChannelFutureListener) channelFuture -> {
            if (channelFuture.isSuccess()) {
                log.info("【代理服务】【目标->{}:{}】连接成功", host, port);
                connectCallBack.success(channelFuture.channel());
            } else {
                log.error("【代理服务】【目标->{}:{}】连接失败, 原因: {}", host, port, channelFuture.cause() != null ? channelFuture.cause().getMessage() : "未知");
                connectCallBack.error(channelFuture.channel());
            }
        });
    }

    /**
     * 绑定两个通道（双向绑定）
     */
    public static void bindChannel(Channel serverChannel, Channel connectChannel) {
        if (serverChannel == null || connectChannel == null) {
            log.warn("【代理服务】绑定通道失败，通道为NULL");
            return;
        }

        if (!serverChannel.isActive() || !connectChannel.isActive()) {
            log.warn("【代理服务】绑定通道失败，通道不活跃");
            return;
        }

        // 移除旧的绑定（如果存在）
        unbindChannel(serverChannel);

        // 建立双向绑定
        serverChannel.attr(Constants.NEXT_CHANNEL).set(connectChannel);
        connectChannel.attr(Constants.NEXT_CHANNEL).set(serverChannel);

        log.info("【代理服务】绑定通道: {} <-> {}", serverChannel.id().asShortText(), connectChannel.id().asShortText());
    }

    /**
     * 解除通道绑定
     */
    public static void unbindChannel(Channel serverChannel) {
        if (serverChannel == null) {
            return;
        }
        // 刷新缓冲区
        serverChannel.flush();
        // 解除绑定
        Channel boundChannel = serverChannel.attr(Constants.NEXT_CHANNEL).getAndSet(null);
        if (boundChannel != null && boundChannel.isActive()) {
            boundChannel.attr(Constants.NEXT_CHANNEL).set(null);
            log.debug("【代理服务】解除绑定: {} <-> {}", serverChannel.id().asShortText(), boundChannel.id().asShortText());
        }
    }


    /**
     * 优雅关闭
     */
    public static void shutdown() {
        log.info("【代理服务】开始关闭...");

        if (!eventLoopGroup.isShutdown()) {
            eventLoopGroup.shutdownGracefully(100, 500, TimeUnit.MILLISECONDS)
                    .addListener(future -> {
                        if (future.isSuccess()) {
                            log.info("【代理服务】EventLoopGroup关闭成功");
                        }
                    });
        }

        log.info("【代理服务】关闭完成");
    }
}