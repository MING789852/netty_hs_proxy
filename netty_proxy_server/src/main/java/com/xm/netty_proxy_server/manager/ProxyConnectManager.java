package com.xm.netty_proxy_server.manager;

import com.xm.netty_proxy_common.callback.ConnectCallBack;
import com.xm.netty_proxy_common.key.Constants;
import com.xm.netty_proxy_common.msg.ProxyMessage;
import com.xm.netty_proxy_common.msg.ProxyMessageManager;
import com.xm.netty_proxy_common.msg.ProxyMessageType;
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
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ProxyConnectManager {
    private static final Bootstrap bootstrap=new Bootstrap();

    @Getter
    private static final ProxyMessageManager proxyMessageManager;

    static {
        proxyMessageManager=new ProxyMessageManager(Config.username,Config.password);
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
            serverChannel.writeAndFlush(proxyMessageManager.wrapServerProxyFail(host,port));
        }
    }
}
