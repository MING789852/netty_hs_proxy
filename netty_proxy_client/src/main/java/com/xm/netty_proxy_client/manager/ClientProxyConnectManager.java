package com.xm.netty_proxy_client.manager;

import com.xm.netty_proxy_client.config.ClientConfig;
import com.xm.netty_proxy_client.proxyHandler.ClientReceiveMessageHandler;
import com.xm.netty_proxy_common.callback.ConnectCallBack;
import com.xm.netty_proxy_common.decoder.MLengthFieldBasedFrameDecoder;
import com.xm.netty_proxy_common.decoder.ProxyMessageDecoder;
import com.xm.netty_proxy_common.encoder.ProxyMessageEncoder;
import com.xm.netty_proxy_common.msg.ProxyMessage;
import com.xm.netty_proxy_common.msg.ProxyMessageManager;
import com.xm.netty_proxy_common.msg.ProxyRequest;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.pool.*;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.FutureListener;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class ClientProxyConnectManager {

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
        proxyMessageManager = new ProxyMessageManager(ClientConfig.USERNAME, ClientConfig.PASSWORD);

        // 初始化连接池
        initializeChannelPool();
    }

    private static boolean isCoreHandler(String name) {
        // 基础编解码器、游离消息回收站 以及 Netty原生节点，防止异常清理
        return name.contains("FrameDecoder") ||
                name.contains("ProxyMessageEncoder") ||
                name.contains("ProxyMessageDecoder") ||
                name.equals("PoolIdleDiscardHandler") || // 【新增】保留游离消息兜底处理节点
                name.contains("HeadContext") ||
                name.contains("TailContext") ||
                "head".equals(name) ||
                "tail".equals(name);
    }

    /**
     * 初始化连接池
     */
    private static void initializeChannelPool() {
        try {
            Bootstrap poolBootstrap = new Bootstrap()
                    .group(eventLoopGroup)
                    .channel(NioSocketChannel.class)
                    .remoteAddress(ClientConfig.SERVER_HOST, ClientConfig.SERVER_PORT)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, ClientConfig.PROXY_CONNECT_TIMEOUT)
                    .option(ChannelOption.TCP_NODELAY, true);

            fixedChannelPool = new FixedChannelPool(
                    poolBootstrap,
                    new ChannelPoolHandler() {
                        @Override
                        public void channelCreated(Channel channel) {
                            log.info("【代理池】【新建连接】{}", getPoolStatus());
                            ChannelPipeline pipeline = channel.pipeline();
                            pipeline.addLast(new MLengthFieldBasedFrameDecoder());
                            pipeline.addLast(new ProxyMessageEncoder());
                            pipeline.addLast(new ProxyMessageDecoder());

                            //游离消息回收站：必须放在 Pipeline 核心逻辑的最后
                            pipeline.addLast("PoolIdleDiscardHandler", new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                    if (msg instanceof ProxyMessage) {
                                        ProxyMessage pm = (ProxyMessage) msg;
                                        // 妥善释放 ByteBuf，防止内存泄漏
                                        if (pm.getData() != null && pm.getData().refCnt() > 0) {
                                            ReferenceCountUtil.release(pm.getData());
                                        }
                                        log.trace("【代理池】成功拦截并丢弃池中游离消息, type: {}", pm.getType());
                                    } else {
                                        ReferenceCountUtil.release(msg);
                                    }
                                }

                                @Override
                                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                    // 忽略空闲连接上的读写异常
                                }
                            });
                        }

                        @Override
                        public void channelReleased(Channel channel) {
                            // 强制异步清理业务Handler，防止同步移除导致的 IO 状态异常
                            channel.eventLoop().execute(() -> {
                                try {
                                    ChannelPipeline pipeline = channel.pipeline();
                                    for (String name : pipeline.names()) {
                                        // 只保留基础编解码器、兜底处理器和自带核心Handler
                                        if (!isCoreHandler(name)) {
                                            pipeline.remove(name);
                                        }
                                    }
                                    log.debug("【代理池】连接清理完成并归还: {}", channel.id().asShortText());
                                } catch (Exception e) {
                                    log.error("【代理池】清理连接时出错: {}", e.getMessage());
                                }
                            });
                        }

                        @Override
                        public void channelAcquired(Channel channel) {
                            log.info("【代理池】【获取连接】{}", getPoolStatus());
                        }
                    },
                    ClientConfig.CLIENT_POOL_SIZE // 最大连接数
            );

            log.info("【代理池】连接池初始化完成，最大连接数: {}", ClientConfig.CLIENT_POOL_SIZE);
        } catch (Exception e) {
            log.error("【代理池】连接池初始化失败", e);
            throw new RuntimeException("连接池初始化失败", e);
        }
    }

    /**
     * 串行化配置Pipeline与发送请求
     */
    private static void addProxyHandlerAndSendRequest(ConnectCallBack connectCallBack,
                                                      Channel localChannel,
                                                      Channel proxyServerChannel,
                                                      ProxyRequest proxyRequest) {
        // 保证在同一个EventLoop生命周期内完成Pipeline的挂载，然后再下发请求
        proxyServerChannel.eventLoop().execute(() -> {
            try {
                // 清理旧的处理器并添加新的
                ChannelPipeline pipeline = proxyServerChannel.pipeline();
                ClientReceiveMessageHandler handler = pipeline.get(ClientReceiveMessageHandler.class);
                if (handler != null) {
                    log.debug("【代理池】清理旧的ReceiveProxyMessageHandler");
                    pipeline.remove(ClientReceiveMessageHandler.class);
                }

                // 【修复逻辑】添加新的接收处理器：必须插入在 PoolIdleDiscardHandler 的前面，优先拦截业务消息
                if (pipeline.get("PoolIdleDiscardHandler") != null) {
                    pipeline.addBefore("PoolIdleDiscardHandler", ClientConfig.PROXY_SERVER_MESSAGE_HANDLER,
                            new ClientReceiveMessageHandler(connectCallBack, localChannel, proxyRequest.getTargetHost(), proxyRequest.getTargetPort()));
                } else {
                    pipeline.addLast(ClientConfig.PROXY_SERVER_MESSAGE_HANDLER,
                            new ClientReceiveMessageHandler(connectCallBack, localChannel, proxyRequest.getTargetHost(), proxyRequest.getTargetPort()));
                }

                // 确认Handler已经妥当放在Pipeline中了，现在发请求才安全
                sendBuildConnectRequest(connectCallBack, localChannel, proxyServerChannel, proxyRequest);
            } catch (Exception e) {
                log.error("【代理池】配置代理通道并请求异常", e);
                connectCallBack.error(proxyServerChannel);
            }
        });
    }

    /**
     * 发送建立连接请求
     */
    private static void sendBuildConnectRequest(ConnectCallBack connectCallBack,
                                                Channel localChannel,
                                                Channel proxyServerChannel,
                                                ProxyRequest proxyRequest) {
        if (localChannel == null || !localChannel.isActive()){
            log.error("【代理池】【目标: {}:{}】本地通道不活跃", proxyRequest.getTargetHost(), proxyRequest.getTargetPort());
            connectCallBack.error(proxyServerChannel);
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
        // 归还到连接池
        fixedChannelPool.release(proxyChannel);
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
        getProxyConnectFromPool(connectCallBack, localChannel, proxyRequest, requestKey, retryCounter);
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
                Channel proxyChannel = channelFuture.getNow();
                if (proxyChannel != null && proxyChannel.isActive() && proxyChannel.isWritable()) {
                    retryCounters.remove(requestKey);
                    if (proxyChannel.isActive()) {
                        addProxyHandlerAndSendRequest(connectCallBack, localChannel, proxyChannel, proxyRequest);
                    } else {
                        log.warn("【代理池】获取到的连接在配置处理器前已失效，重试第{}次", retryCounter.incrementAndGet());
                        fixedChannelPool.release(proxyChannel);
                        getProxyConnect(connectCallBack, localChannel, proxyRequest);
                    }
                } else {
                    log.warn("【代理池】获取到无效或不可写连接，重试第{}次", retryCounter.incrementAndGet());
                    if (proxyChannel != null) {
                        fixedChannelPool.release(proxyChannel);
                    }
                    getProxyConnect(connectCallBack, localChannel, proxyRequest);
                }
            } else {
                log.error("【代理池】获取连接失败, 原因: {}, 重试第{}次",
                        channelFuture.cause() != null ? channelFuture.cause().getMessage() : "未知",
                        retryCounter.incrementAndGet());
                getProxyConnect(connectCallBack, localChannel, proxyRequest);
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
     * 关闭连接池和资源
     */
    public static void shutdown() {
        log.info("【代理池】开始关闭...");

        if (fixedChannelPool != null) {
            fixedChannelPool.close();
            log.info("【代理池】已关闭");
        }

        retryCounters.clear();

        eventLoopGroup.shutdownGracefully();
        log.info("【代理池】EventLoopGroup已关闭");

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
                ClientConfig.CLIENT_POOL_SIZE - acquiredChannelCount);
    }
}