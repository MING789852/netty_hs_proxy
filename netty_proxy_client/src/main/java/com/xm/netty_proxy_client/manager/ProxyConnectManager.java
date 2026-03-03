package com.xm.netty_proxy_client.manager;

import com.xm.netty_proxy_client.config.Config;
import com.xm.netty_proxy_client.proxyHandler.ReceiveProxyMessageHandler;
import com.xm.netty_proxy_common.callback.ConnectCallBack;
import com.xm.netty_proxy_common.decoder.MLengthFieldBasedFrameDecoder;
import com.xm.netty_proxy_common.decoder.ProxyMessageDecoder;
import com.xm.netty_proxy_common.encoder.ProxyMessageEncoder;
import com.xm.netty_proxy_common.msg.ProxyMessageManager;
import com.xm.netty_proxy_common.msg.ProxyRequest;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.pool.*;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.FutureListener;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

@Slf4j
public class ProxyConnectManager {

    // 非连接池模式的Bootstrap
    private static final Bootstrap nonPoolBootstrap;

    private static final NioEventLoopGroup eventLoopGroup = new NioEventLoopGroup();

    // 连接池
    private static FixedChannelPool fixedChannelPool;

    @Getter
    private static final ProxyMessageManager proxyMessageManager;

    // 重试计数器（按请求）
    private static final ConcurrentHashMap<String, AtomicInteger> retryCounters = new ConcurrentHashMap<>();
    private static final int MAX_RETRY_COUNT = 3;

    static {
        // 初始化代理消息管理器
        proxyMessageManager = new ProxyMessageManager(Config.USERNAME, Config.PASSWORD);

        // 初始化非连接池模式的Bootstrap
        nonPoolBootstrap = new Bootstrap()
                .group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Config.PROXY_CONNECT_TIMEOUT)
                .option(ChannelOption.TCP_NODELAY, true);

        // 初始化连接池（如果启用）
        if (Config.CLIENT_OPEN_POOL) {
            initializeChannelPool();
        }
    }

    /**
     * 初始化连接池
     */
    private static void initializeChannelPool() {
        try {
            Bootstrap poolBootstrap = new Bootstrap()
                    .group(eventLoopGroup)
                    .channel(NioSocketChannel.class)
                    .remoteAddress(Config.SERVER_HOST, Config.SERVER_PORT)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Config.PROXY_CONNECT_TIMEOUT)
                    .option(ChannelOption.TCP_NODELAY, true);

            fixedChannelPool = new FixedChannelPool(
                    poolBootstrap,
                    new ChannelPoolHandler() {
                        @Override
                        public void channelCreated(Channel channel) {
                        }

                        @Override
                        public void channelReleased(Channel channel) {
                            if (channel != null && channel.isActive()) {
                                // 清理可能残留的处理器
                                cleanProxyHandler(channel);
                                log.info("【代理池】【归还连接】{}", getPoolStatus());
                            }
                        }

                        @Override
                        public void channelAcquired(Channel channel) {
                            log.info("【代理池】【获取连接】{}", getPoolStatus());
                        }
                    },
                    Config.CLIENT_POOL_SIZE // 最大连接数
            );

            log.info("【代理池】连接池初始化完成，最大连接数: {}", Config.CLIENT_POOL_SIZE);
        } catch (Exception e) {
            log.error("【代理池】连接池初始化失败", e);
            throw new RuntimeException("连接池初始化失败", e);
        }
    }

    /**
     * 清理接收处理器
     */
    private static void cleanProxyHandler(Channel channel) {
        if (channel != null && channel.isActive()) {
            ChannelPipeline pipeline = channel.pipeline();
            ReceiveProxyMessageHandler handler = pipeline.get(ReceiveProxyMessageHandler.class);
            if (handler != null) {
                pipeline.remove(ReceiveProxyMessageHandler.class);
                log.debug("【代理池】清理ReceiveProxyMessageHandler, 连接ID: {}", channel.id().asShortText());
            }
        }
    }


    private static void addProxyHandler(ConnectCallBack connectCallBack,
                                        Channel localChannel,
                                        Channel proxyServerChannel,
                                        ProxyRequest proxyRequest){
        // 清理旧的处理器并添加新的
        ChannelPipeline pipeline = proxyServerChannel.pipeline();
        cleanProxyHandler(proxyServerChannel);

        // 添加新的接收处理器
        pipeline.addLast(new MLengthFieldBasedFrameDecoder());
        pipeline.addLast(new ProxyMessageEncoder());
        pipeline.addLast(new ProxyMessageDecoder());
        pipeline.addLast(Config.PROXY_SERVER_MESSAGE_HANDLER,
                new ReceiveProxyMessageHandler(connectCallBack, localChannel, proxyRequest.getTargetHost(), proxyRequest.getTargetPort()));
    }

    /**
     * 发送建立连接请求
     */
    private static void sendBuildConnectRequest(ConnectCallBack connectCallBack,
                                                Channel localChannel,
                                                Channel proxyServerChannel,
                                                ProxyRequest proxyRequest) {

        // 检查通道是否活跃
        if (proxyServerChannel == null || !proxyServerChannel.isActive()) {
            log.error("【代理池】【目标: {}:{}】代理通道不活跃", proxyRequest.getTargetHost(), proxyRequest.getTargetPort());
            connectCallBack.error(null);
            return;
        }
        if (localChannel == null || !localChannel.isActive()){
            log.error("【代理池】【目标: {}:{}】本地通道不活跃", proxyRequest.getTargetHost(), proxyRequest.getTargetPort());
            connectCallBack.error(null);
            return;
        }
        // 发送建立连接请求
        proxyServerChannel.writeAndFlush(
                proxyMessageManager.wrapBuildConnect(proxyRequest.getTargetHost(), proxyRequest.getTargetPort())
        ).addListener((ChannelFutureListener) channelFuture -> {
            if (!channelFuture.isSuccess()) {
                log.error("【代理池】【目标: {}:{}】发送建立代理连接请求失败, 原因: {}", proxyRequest.getTargetHost(), proxyRequest.getTargetPort(),
                        channelFuture.cause() != null ? channelFuture.cause().getMessage() : "未知");
                connectCallBack.error(proxyServerChannel);
            }
        });
    }

    /**
     * 归还代理连接
     */
    public static void returnProxyConnect(Channel proxyChannel) {
        if (proxyChannel == null) {
            return;
        }

        if (Config.CLIENT_OPEN_POOL) {
            if (proxyChannel.isActive()) {
                // 归还到连接池
                proxyChannel.flush();
                fixedChannelPool.release(proxyChannel);
                log.debug("【代理池】归还连接到池中, 通道ID: {}", proxyChannel.id().asShortText());
            } else {
                // 通道已关闭，通知连接池
                proxyChannel.close();
                log.error("【代理池】通道已关闭，无法归还到连接池, 通道ID: {}",
                        proxyChannel.id().asShortText());
            }
        } else {
            // 非连接池模式，直接关闭连接
            if (proxyChannel.isActive()) {
                proxyChannel.close().addListener(future -> {
                    if (future.isSuccess()) {
                        log.debug("【代理池】关闭非池化连接, 通道ID: {}", proxyChannel.id().asShortText());
                    }
                });
            }
        }
    }

    /**
     * 获取代理连接（带重试机制）
     */
    public static void getProxyConnect(ConnectCallBack connectCallBack,
                                       Channel localChannel,
                                       ProxyRequest proxyRequest) {

        String requestKey = generateRequestKey(localChannel, proxyRequest);
        AtomicInteger retryCounter = retryCounters.computeIfAbsent(requestKey, k -> new AtomicInteger(0));

        if (retryCounter.get() >= MAX_RETRY_COUNT) {
            log.error("【代理池】达到最大重试次数({}), 放弃获取代理连接, 请求: {}",
                    MAX_RETRY_COUNT, proxyRequest);
            retryCounters.remove(requestKey);
            connectCallBack.error(null);
            return;
        }

        if (Config.CLIENT_OPEN_POOL) {
            getProxyConnectFromPool(connectCallBack, localChannel, proxyRequest, requestKey, retryCounter);
        } else {
            getNewProxyConnect(connectCallBack, localChannel, proxyRequest, requestKey, retryCounter);
        }
    }

    /**
     * 从连接池获取代理连接
     */
    private static void getProxyConnectFromPool(ConnectCallBack connectCallBack,
                                                Channel localChannel,
                                                ProxyRequest proxyRequest,
                                                String requestKey,
                                                AtomicInteger retryCounter) {

        fixedChannelPool.acquire().addListener((FutureListener<Channel>) channelFuture -> {
            if (channelFuture.isSuccess()) {
                Channel channel = channelFuture.getNow();
                if (channel != null && channel.isActive()) {
                    // 成功获取连接，清理重试计数器
                    retryCounters.remove(requestKey);
                    // 添加处理器
                    addProxyHandler(connectCallBack, localChannel, channel, proxyRequest);
                    // 发送建立连接请求
                    sendBuildConnectRequest(connectCallBack, localChannel, channel, proxyRequest);
                } else {
                    // 获取的连接无效，重试
                    log.warn("【代理池】获取到无效连接，重试第{}次", retryCounter.incrementAndGet());
                    if (channel != null) {
                        fixedChannelPool.release(channel);
                    }
                    scheduleRetry(() -> getProxyConnect(connectCallBack, localChannel, proxyRequest),
                            retryCounter.get());
                }
            } else {
                // 获取连接失败，重试
                log.error("【代理池】获取连接失败, 原因: {}, 重试第{}次",
                        channelFuture.cause() != null ? channelFuture.cause().getMessage() : "未知",
                        retryCounter.incrementAndGet());
                scheduleRetry(() -> getProxyConnect(connectCallBack, localChannel, proxyRequest),
                        retryCounter.get());
            }
        });
    }

    /**
     * 创建新的代理连接
     */
    private static void getNewProxyConnect(ConnectCallBack connectCallBack,
                                           Channel localChannel,
                                           ProxyRequest proxyRequest,
                                           String requestKey,
                                           AtomicInteger retryCounter) {

        nonPoolBootstrap.connect(Config.SERVER_HOST, Config.SERVER_PORT)
                .addListener((ChannelFutureListener) channelFuture -> {
                    if (channelFuture.isSuccess()) {
                        Channel channel = channelFuture.channel();
                        long now = System.currentTimeMillis();
                        // 成功建立连接，清理重试计数器
                        retryCounters.remove(requestKey);
                        log.info("【代理池】创建新代理连接成功, 通道ID: {}, 时间: {}", channel.id().asShortText(), now);
                        // 添加处理器
                        addProxyHandler(connectCallBack, localChannel, channel, proxyRequest);
                        // 发送建立连接请求
                        sendBuildConnectRequest(connectCallBack, localChannel, channel, proxyRequest);
                    } else {
                        // 连接失败，重试
                        log.error("【代理池】创建新代理连接失败, 原因: {}, 重试第{}次",
                                channelFuture.cause() != null ? channelFuture.cause().getMessage() : "未知",
                                retryCounter.incrementAndGet());
                        scheduleRetry(() -> getProxyConnect(connectCallBack, localChannel, proxyRequest),
                                retryCounter.get());
                    }
                });
    }

    /**
     * 生成请求唯一键
     */
    private static String generateRequestKey(Channel localChannel, ProxyRequest proxyRequest) {
        return localChannel.id().asLongText() + "-" +
                proxyRequest.getTargetHost() + ":" + proxyRequest.getTargetPort();
    }

    /**
     * 调度重试（带指数退避）
     */
    private static void scheduleRetry(Runnable retryTask, int retryCount) {
        long delayMs = Math.min(1000 * (1L << (retryCount - 1)), 10000); // 指数退避，最大10秒
        eventLoopGroup.schedule(retryTask, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 关闭连接池和资源
     */
    public static void shutdown() {
        log.info("【代理池】开始关闭...");

        // 关闭连接池
        if (fixedChannelPool != null) {
            fixedChannelPool.close();
            log.info("【代理池】已关闭");
        }

        // 清理跟踪数据
        retryCounters.clear();

        // 关闭EventLoopGroup
        if (nonPoolBootstrap != null && nonPoolBootstrap.config().group() != null) {
            nonPoolBootstrap.config().group().shutdownGracefully();
            log.info("【代理池】EventLoopGroup已关闭");
        }

        log.info("【代理池】关闭完成");
    }

    /**
     * 获取连接池状态
     */
    public static String getPoolStatus() {
        if (fixedChannelPool == null) {
            return "连接池未启用";
        }
        int acquiredChannelCount = fixedChannelPool.acquiredChannelCount();
        return String.format("连接池状态: 已获取连接数=%d, 空闲连接数=%d",
                acquiredChannelCount,
                Config.CLIENT_POOL_SIZE - acquiredChannelCount);
    }
}