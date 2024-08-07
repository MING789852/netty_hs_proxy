package com.xm.netty_proxy_client.manager;

import com.xm.netty_proxy_client.config.Config;
import com.xm.netty_proxy_client.proxyHandler.ReceiveProxyMessageHandler;
import com.xm.netty_proxy_common.callback.ConnectCallBack;
import com.xm.netty_proxy_common.decoder.MLengthFieldBasedFrameDecoder;
import com.xm.netty_proxy_common.decoder.ProxyMessageDecoder;
import com.xm.netty_proxy_common.encoder.ProxyMessageEncoder;
import com.xm.netty_proxy_common.msg.ProxyMessage;
import com.xm.netty_proxy_common.msg.ProxyMessageManager;
import com.xm.netty_proxy_common.msg.ProxyRequest;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.pool.ChannelPoolHandler;
import io.netty.channel.pool.FixedChannelPool;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.FutureListener;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

@Slf4j
public class ProxyConnectManager {

    private static final Bootstrap bootstrap=new Bootstrap();

    private static FixedChannelPool fixedChannelPool;

    @Getter
    private static final ProxyMessageManager proxyMessageManager;

    static {
        bootstrap
                .group(new NioEventLoopGroup())
                .channel(NioSocketChannel.class);
        proxyMessageManager=new ProxyMessageManager(Config.username,Config.password);
        if (Config.clientOpenPool){
            bootstrap.remoteAddress(Config.serverHost,Config.serverPort);
            fixedChannelPool=new FixedChannelPool(bootstrap, new ChannelPoolHandler() {
                @Override
                public void channelReleased(Channel channel) {
                    log.info("[代理池]归还连接,已连接数量->{}",fixedChannelPool.acquiredChannelCount());
                }

                @Override
                public void channelAcquired(Channel channel) {
                    log.info("[代理池]获取连接池连接,已连接数量->{}",fixedChannelPool.acquiredChannelCount());
                }

                @Override
                public void channelCreated(Channel channel) {
                    log.info("[代理池]创建新连接,已连接数量->{}",fixedChannelPool.acquiredChannelCount());
                    ChannelPipeline pipeline=channel.pipeline();
                    pipeline.addLast(new MLengthFieldBasedFrameDecoder());
                    //解析数据
                    pipeline.addLast(new ProxyMessageEncoder());
                    pipeline.addLast(new ProxyMessageDecoder());
                    //20秒一次发送心跳
                    pipeline.addLast(new IdleStateHandler(0, Config.writerIdleTime, 0, TimeUnit.SECONDS));
                }
            },Config.clientPoolSize);
        }else {
            bootstrap.handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel socketChannel) {
                    ChannelPipeline pipeline=socketChannel.pipeline();
                    pipeline.addLast(new MLengthFieldBasedFrameDecoder());
                    //解析数据
                    pipeline.addLast(new ProxyMessageEncoder());
                    pipeline.addLast(new ProxyMessageDecoder());
                    //20秒一次发送心跳
                    pipeline.addLast(new IdleStateHandler(0, Config.writerIdleTime, 0, TimeUnit.SECONDS));
                }
            });
        }
    }





    private static void sendBuildConnectRequest(boolean isPoolChannel, ConnectCallBack connectCallBack, Channel localChannel, Channel proxyServerChannel, ProxyRequest proxyRequest){
        //处理服务端返回数据
        ChannelPipeline pipeline = proxyServerChannel.pipeline();
        ReceiveProxyMessageHandler receiveProxyMessageHandler = pipeline.get(ReceiveProxyMessageHandler.class);
        if (receiveProxyMessageHandler!=null){
            pipeline.replace(Config.ProxyServerMessageHandler,Config.ProxyServerMessageHandler,new ReceiveProxyMessageHandler(isPoolChannel,connectCallBack,localChannel));
        }else {
            pipeline.addLast(Config.ProxyServerMessageHandler,new ReceiveProxyMessageHandler(isPoolChannel,connectCallBack,localChannel));
        }
        //发送建立连接请求
        proxyServerChannel.writeAndFlush(proxyMessageManager.wrapBuildConnect(proxyRequest.getTargetHost(),proxyRequest.getTargetPort()))
                .addListener((ChannelFutureListener) channelFuture -> {
                    if (!channelFuture.isSuccess()){
                        log.info("[代理客户端]发送请求建立代理连接服务失败");
                        connectCallBack.error(channelFuture.channel());
                    }
                });
    }

    public static void returnProxyConnect(Channel proxyChannel){
        if (Config.clientOpenPool){
            if (proxyChannel!=null) {
                fixedChannelPool.release(proxyChannel);
            }
        }
    }

    public static void getProxyConnect(ConnectCallBack connectCallBack,Channel localChannel, ProxyRequest proxyRequest){
        if (Config.clientOpenPool){
            fixedChannelPool.acquire().addListener((FutureListener<Channel>) channelFuture -> {
                if (channelFuture.isSuccess()){
                    Channel channel = channelFuture.getNow();
                    if (!channel.isActive()){
                        log.error("[代理池]代理池获取连接未激活，重新获取");
                        fixedChannelPool.release(channel);
                        getProxyConnect(connectCallBack, localChannel, proxyRequest);
                    }else {
                        //发送建立连接请求
                        sendBuildConnectRequest(true,connectCallBack,localChannel,channel,proxyRequest);
                    }
                }
            });
        }else {
            bootstrap.connect(Config.serverHost,Config.serverPort).addListener((ChannelFutureListener) channelFuture -> {
                if (channelFuture.isSuccess()){
                    log.info("[代理池]创建新代理连接成功");
                    //发送建立连接请求
                    sendBuildConnectRequest(false,connectCallBack,localChannel,channelFuture.channel(),proxyRequest);
                }else {
                    log.info("[代理池]创建新代理连接失败");
                    connectCallBack.error(channelFuture.channel());
                }
            });
        }
    }
}
