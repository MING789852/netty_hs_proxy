package com.xm.netty_proxy_client.boot;

import com.xm.netty_proxy_client.config.Config;
import com.xm.netty_proxy_client.localHandler.HttpServerProxyClientHandler;
import com.xm.netty_proxy_client.localHandler.SocksServerProxyClientHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.socksx.SocksPortUnificationServerHandler;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ProxyClientBoot {
    private final EventLoopGroup bossGroup=new NioEventLoopGroup();
    private final EventLoopGroup workGroup=new NioEventLoopGroup();

    private final int port;

    public ProxyClientBoot(int port){
        this.port=port;
    }

    public void run(){
        try {
            ServerBootstrap serverBootstrap=new ServerBootstrap();
            serverBootstrap
                    .group(bossGroup,workGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel socketChannel) throws Exception {
                            ChannelPipeline pipeline =socketChannel.pipeline();
                            if ("socks".equals(Config.proxyType)){
                                pipeline.addLast(Config.SocksPortUnificationServerHandler,new SocksPortUnificationServerHandler());
                                pipeline.addLast(Config.SocksServerProxyClientHandler,new SocksServerProxyClientHandler());
                            }

                            if ("http".equals(Config.proxyType)){
                                pipeline.addLast(
                                        new HttpRequestDecoder(),
                                        new HttpResponseEncoder(),
                                        new HttpObjectAggregator(3 * 1024 * 1024),
                                        new HttpServerProxyClientHandler());
                            }
                        }
                    });
            log.debug("bind port : " + port);
            ChannelFuture future = serverBootstrap.bind(port);
            future.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            log.error("启动失败");
        } finally {
            bossGroup.shutdownGracefully();
            workGroup.shutdownGracefully();
        }
    }

    public static void main(String[] args) {
        ProxyClientBoot localSock5ClientBoot=new ProxyClientBoot(Config.clientPort);
        localSock5ClientBoot.run();
    }
}
