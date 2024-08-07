package com.xm.netty_proxy_server.manager;

import com.xm.netty_proxy_common.callback.ConnectCallBack;
import com.xm.netty_proxy_common.key.Constants;
import com.xm.netty_proxy_common.msg.ProxyMessage;
import com.xm.netty_proxy_server.config.Config;
import com.xm.netty_proxy_server.proxyHandler.ProxyMessageHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ProxyConnectManager {
    private static final Bootstrap bootstrap=new Bootstrap();

    static {
        bootstrap
                .group(new NioEventLoopGroup())
                .channel(NioSocketChannel.class)
                .handler(new LoggingHandler(LogLevel.INFO))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,10000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel socketChannel) throws Exception {
                        ChannelPipeline pipeline=socketChannel.pipeline();
                        pipeline.addLast(new ProxyMessageHandler());
                    }
                });
    }

    public static void connect(String host, int port, ConnectCallBack connectCallBack) {
        bootstrap.connect(host,port).addListener((ChannelFutureListener) channelFuture -> {
            if (channelFuture.isSuccess()){
                //添加数据回调处理
                log.info("[代理服务]连接成功->{}:{}",host,port);
                connectCallBack.success(channelFuture.channel(),false);
            }else {
                log.info("[代理服务]连接失败->{}:{}",host,port);
                connectCallBack.error(channelFuture.channel());
            }
        });
    }

    public static void bindChannel(Channel serverChannel,Channel connectChannel){
        log.info("[代理服务]绑定代理服务连接->{}和代理目标连接->{}的关联",serverChannel.remoteAddress(),connectChannel.remoteAddress());
        //绑定连接
        serverChannel.attr(Constants.NEXT_CHANNEL).set(connectChannel);
        connectChannel.attr(Constants.NEXT_CHANNEL).set(serverChannel);
    }

    public static void unBindChannel(Channel serverChannel){
        if (serverChannel==null){
            return;
        }
        Channel connectChannel = serverChannel.attr(Constants.NEXT_CHANNEL).get();
        if (connectChannel!=null){
            log.info("[代理服务]解除代理服务连接->{}和代理目标连接->{}的关联",serverChannel.remoteAddress(),connectChannel.remoteAddress());
            connectChannel.attr(Constants.NEXT_CHANNEL).set(null);
        }
        serverChannel.attr(Constants.NEXT_CHANNEL).set(null);
    }

    public static  void  notifyServerProxyFail(Channel serverChannel,String host,int port) {
        unBindChannel(serverChannel);
        if (serverChannel!=null) {
            serverChannel.writeAndFlush(ProxyConnectManager.wrapServerProxyFail(host,port));
        }
    }

    public static ProxyMessage wrapServerProxyFail(String host,int port){
        ProxyMessage proxyMessage=new ProxyMessage();
        proxyMessage.setType(ProxyMessage.SERVER_PROXY_FAIL);
        proxyMessage.setUsername(Config.username);
        proxyMessage.setPassword(Config.password);
        proxyMessage.setTargetHost(host);
        proxyMessage.setTargetPort(port);
        proxyMessage.setData("4".getBytes());

        return proxyMessage;
    }

    public static ProxyMessage wrapNotifyServerCloseAck(){
        ProxyMessage proxyMessage=new ProxyMessage();
        proxyMessage.setType(ProxyMessage.NOTIFY_SERVER_CLOSE_ACK);
        proxyMessage.setUsername(Config.username);
        proxyMessage.setPassword(Config.password);
        proxyMessage.setTargetHost("5");
        proxyMessage.setTargetPort(5);
        proxyMessage.setData("5".getBytes());
        return proxyMessage;
    }

    public static ProxyMessage wrapConnectSuccess(String host,int port){
        ProxyMessage proxyMessage=new ProxyMessage();
        proxyMessage.setType(ProxyMessage.CONNECT_SUCCESS);
        proxyMessage.setUsername(Config.username);
        proxyMessage.setPassword(Config.password);
        proxyMessage.setTargetHost(host);
        proxyMessage.setTargetPort(port);
        proxyMessage.setData("2".getBytes());

        return proxyMessage;
    }

    public static ProxyMessage wrapTransfer(ByteBuf byteBuf){
        ProxyMessage proxyMessage=new ProxyMessage();
        proxyMessage.setType(ProxyMessage.TRANSFER);
        proxyMessage.setUsername(Config.username);
        proxyMessage.setPassword(Config.password);
        proxyMessage.setTargetHost("3");
        proxyMessage.setTargetPort(8888);
        byte[] data=new byte[byteBuf.readableBytes()];
        byteBuf.readBytes(data);
        ReferenceCountUtil.release(byteBuf);
        proxyMessage.setData(data);
        return proxyMessage;
    }
}
