package com.xm.netty_proxy_client.boot;

import com.xm.netty_proxy_client.config.Config;
import com.xm.netty_proxy_client.localHandler.XUnificationServerHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioChannelOption;
import io.netty.channel.socket.nio.NioServerSocketChannel;
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
                    .option(NioChannelOption.TCP_NODELAY,true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel socketChannel) throws Exception {
                            ChannelPipeline pipeline =socketChannel.pipeline();
                            pipeline.addLast(new XUnificationServerHandler());
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
