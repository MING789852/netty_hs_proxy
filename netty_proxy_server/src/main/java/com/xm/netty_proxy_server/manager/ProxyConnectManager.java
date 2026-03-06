package com.xm.netty_proxy_server.manager;

import com.xm.netty_proxy_common.callback.ConnectCallBack;
import com.xm.netty_proxy_common.msg.ProxyMessageManager;
import com.xm.netty_proxy_server.config.Config;
import com.xm.netty_proxy_server.proxyHandler.ProxyMessageHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

@Slf4j
public class ProxyConnectManager {

    // 共享的EventLoopGroup
    private static final NioEventLoopGroup eventLoopGroup= new NioEventLoopGroup();
    @Getter
    private static final ProxyMessageManager proxyMessageManager;

    // 连接超时配置
    private static final int CONNECT_TIMEOUT_MS = 5000;

    static {
        // 初始化代理消息管理器
        proxyMessageManager = new ProxyMessageManager(Config.USERNAME, Config.PASSWORD);
    }

    /**
     * 连接到目标服务器
     */
    public static void connect(Channel serverChannel,String host, int port, ConnectCallBack connectCallBack) {
        if (host == null || host.trim().isEmpty() || port <= 0 || port > 65535) {
            log.error("【代理服务】【目标->{}:{}】无效的目标地址", host, port);
            connectCallBack.error(null);
            return;
        }
        // 初始化Bootstrap
        Bootstrap bootstrap = new Bootstrap()
                .group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .handler(new ChannelInitializer<NioSocketChannel>() {
                    @Override
                    protected void initChannel(NioSocketChannel nioSocketChannel) {
                        nioSocketChannel.pipeline().addLast(new ProxyMessageHandler(serverChannel));
                    }
                });
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