package com.xm.netty_proxy_server.boot;

import com.xm.netty_proxy_common.decoder.MLengthFieldBasedFrameDecoder;
import com.xm.netty_proxy_common.decoder.ProxyMessageDecoder;
import com.xm.netty_proxy_common.encoder.ProxyMessageEncoder;
import com.xm.netty_proxy_server.config.ServerConfig;
import com.xm.netty_proxy_server.serverHandler.ServerReceiveMessageHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioChannelOption;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

@Slf4j
public class ProxyServerBoot {

    private final EventLoopGroup bossGroup=new NioEventLoopGroup();
    private final EventLoopGroup workerGroup=new NioEventLoopGroup();
    private final ServerBootstrap bootstrap=new ServerBootstrap();

    private final int port;

    public ProxyServerBoot(int port) {
        this.port = port;
    }


    public void run(){
        try {
            bootstrap.group(bossGroup,workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 2048) // 提高三次握手队列长度
                    .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT) // 强制内存池
                    .childOption(ChannelOption.TCP_NODELAY, true) // 禁用 Nagle 算法，降低延迟
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel socketChannel) throws Exception {
                             ChannelPipeline pipeline =socketChannel.pipeline();
                             pipeline.addLast(new MLengthFieldBasedFrameDecoder());
                             pipeline.addLast(new ProxyMessageDecoder());
                             pipeline.addLast(new ProxyMessageEncoder());
                             pipeline.addLast(new IdleStateHandler(60, 0, 0, TimeUnit.SECONDS));
                             //处理数据
                             pipeline.addLast(new ServerReceiveMessageHandler());
                        }
                    });
            log.debug("bind port : {}", port);
            ChannelFuture future = bootstrap.bind(port);
            future.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            log.error("启动失败");
        }finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    public static void main(String[] args) {
        ProxyServerBoot proxySock5ServerBoot=new ProxyServerBoot(ServerConfig.SERVER_PORT);
        proxySock5ServerBoot.run();
    }
}
