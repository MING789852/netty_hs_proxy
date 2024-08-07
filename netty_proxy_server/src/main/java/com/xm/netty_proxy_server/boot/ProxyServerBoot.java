package com.xm.netty_proxy_server.boot;

import com.xm.netty_proxy_common.decoder.MLengthFieldBasedFrameDecoder;
import com.xm.netty_proxy_common.decoder.ProxyMessageDecoder;
import com.xm.netty_proxy_common.encoder.ProxyMessageEncoder;
import com.xm.netty_proxy_server.config.Config;
import com.xm.netty_proxy_server.serverHandler.ServerMessageHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
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
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel socketChannel) throws Exception {
                             ChannelPipeline pipeline =socketChannel.pipeline();
                             pipeline.addLast(new MLengthFieldBasedFrameDecoder());
                             pipeline.addLast(new ProxyMessageDecoder());
                             pipeline.addLast(new ProxyMessageEncoder());
                             //n秒内未收到请求，触发userEventTriggered
                             socketChannel.pipeline().addLast(new IdleStateHandler(Config.readerIdleTime, 0, 0, TimeUnit.SECONDS));
                             //处理数据
                             pipeline.addLast(new ServerMessageHandler());
                        }
                    });
            log.debug("bind port : " + port);
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
        ProxyServerBoot proxySock5ServerBoot=new ProxyServerBoot(Config.serverPort);
        proxySock5ServerBoot.run();
    }
}
