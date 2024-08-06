package com.xm.netty_proxy_client.manager;

import com.xm.netty_proxy_client.config.Config;
import com.xm.netty_proxy_client.proxyHandler.ReceiveProxyMessageHandler;
import com.xm.netty_proxy_common.callback.ConnectCallBack;
import com.xm.netty_proxy_common.decoder.MLengthFieldBasedFrameDecoder;
import com.xm.netty_proxy_common.decoder.ProxyMessageDecoder;
import com.xm.netty_proxy_common.encoder.ProxyMessageEncoder;
import com.xm.netty_proxy_common.msg.ProxyMessage;
import com.xm.netty_proxy_common.msg.ProxyRequest;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.pool.ChannelPoolHandler;
import io.netty.channel.pool.FixedChannelPool;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.FutureListener;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ProxyConnectManager {

    private static final Bootstrap bootstrap=new Bootstrap();

    private static FixedChannelPool fixedChannelPool;



    static {
        bootstrap
                .group(new NioEventLoopGroup())
                .channel(NioSocketChannel.class);

        if (Config.clientOpenPool){
            bootstrap.remoteAddress(Config.serverHost,Config.serverPort);
            fixedChannelPool=new FixedChannelPool(bootstrap, new ChannelPoolHandler() {
                @Override
                public void channelReleased(Channel channel) {
                    channel.config().setAutoRead(false);
                    log.info("[代理池]归还连接,已连接数量->{}",fixedChannelPool.acquiredChannelCount());
                }

                @Override
                public void channelAcquired(Channel channel) {
                    channel.config().setAutoRead(true);
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
        proxyServerChannel.writeAndFlush(ProxyConnectManager.wrapBuildConnect(proxyRequest.getTargetHost(),proxyRequest.getTargetPort()))
                .addListener((ChannelFutureListener) channelFuture -> {
                    if (!channelFuture.isSuccess()){
                        log.info("[代理客户端]发送请求建立代理连接服务失败");
                        connectCallBack.error();
                    }
                });
    }

    public static void returnProxyConnect(Channel proxyChannel){
        if (Config.clientOpenPool){
            if (proxyChannel!=null&&proxyChannel.isActive()) {
                fixedChannelPool.release(proxyChannel);
            }
        }
    }

    public static void getProxyConnect(ConnectCallBack connectCallBack,Channel localChannel, ProxyRequest proxyRequest){
        if (Config.clientOpenPool){
            fixedChannelPool.acquire().addListener((FutureListener<Channel>) channelFuture -> {
                if (channelFuture.isSuccess()){
                    Channel channel = channelFuture.getNow();
                    //发送建立连接请求
                    sendBuildConnectRequest(true,connectCallBack,localChannel,channel,proxyRequest);
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
                    connectCallBack.error();
                }
            });
        }
    }

    public static ProxyMessage wrapBuildConnect(String host, int port){
        ProxyMessage proxyMessage=new ProxyMessage();
        proxyMessage.setType(ProxyMessage.BUILD_CONNECT);
        proxyMessage.setUsername(Config.username);
        proxyMessage.setPassword(Config.password);
        proxyMessage.setTargetHost(host);
        proxyMessage.setTargetPort(port);
        proxyMessage.setData("1".getBytes());
        return proxyMessage;
    }

    public static ProxyMessage wrapTransferByteBuf(ByteBuf byteBuf){
        int readableBytes= byteBuf.readableBytes();
        if (readableBytes==0){
            throw new RuntimeException("转发数据为null");
        }
        ProxyMessage proxyMessage=new ProxyMessage();
        proxyMessage.setType(ProxyMessage.TRANSFER);
        proxyMessage.setUsername(Config.username);
        proxyMessage.setPassword(Config.password);
        proxyMessage.setTargetHost("3");
        proxyMessage.setTargetPort(0);
        byte[] data=new byte[readableBytes];
        byteBuf.readBytes(data);
        ReferenceCountUtil.release(byteBuf);
        proxyMessage.setData(data);
        return proxyMessage;
    }


    public static ProxyMessage wrapNotifyServerClose(){
        ProxyMessage proxyMessage=new ProxyMessage();
        proxyMessage.setType(ProxyMessage.NOTIFY_SERVER_CLOSE);
        proxyMessage.setUsername(Config.username);
        proxyMessage.setPassword(Config.password);
        proxyMessage.setTargetHost("4");
        proxyMessage.setTargetPort(4);
        proxyMessage.setData("4".getBytes());

        return proxyMessage;
    }
}
