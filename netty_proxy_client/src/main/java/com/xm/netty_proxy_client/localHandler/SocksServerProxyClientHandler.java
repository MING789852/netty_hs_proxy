package com.xm.netty_proxy_client.localHandler;

import com.xm.netty_proxy_client.config.Config;
import com.xm.netty_proxy_client.manager.ProxyConnectManager;
import com.xm.netty_proxy_client.proxyHandler.SendProxyMessageHandler;
import com.xm.netty_proxy_common.callback.ConnectCallBack;
import com.xm.netty_proxy_common.msg.ProxyRequest;
import io.netty.channel.*;
import io.netty.handler.codec.socksx.SocksMessage;
import io.netty.handler.codec.socksx.v4.DefaultSocks4CommandResponse;
import io.netty.handler.codec.socksx.v4.Socks4CommandRequest;
import io.netty.handler.codec.socksx.v4.Socks4CommandStatus;
import io.netty.handler.codec.socksx.v5.*;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SocksServerProxyClientHandler extends SimpleChannelInboundHandler<SocksMessage> {
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, SocksMessage socksMessage) {
        Channel localChannel = ctx.channel();

        if (socksMessage instanceof Socks4CommandRequest) {
            Socks4CommandRequest request = (Socks4CommandRequest) socksMessage;
            String destAddress = request.dstAddr();
            int destPort = request.dstPort();

            //设置代理信息
            ProxyRequest proxyRequest = new ProxyRequest();
            proxyRequest.setTargetHost(destAddress);
            proxyRequest.setTargetPort(destPort);

            ProxyConnectManager.getProxyConnect(new ConnectCallBack() {
                @Override
                public void success(final Channel proxyServerChannel) {
                    // 修复：确保在Channel所属的EventLoop线程中执行Pipeline操作
                    ctx.channel().eventLoop().execute(() -> {
                        if (!ctx.channel().isActive()) {
                            log.warn("【Socks4代理】【目标->{}:{}】本地通道已关闭", destAddress, destPort);
                            ProxyConnectManager.returnProxyConnect(proxyServerChannel);
                            return;
                        }
                        // 原子性操作：写响应、修改Pipeline
                        ctx.writeAndFlush(new DefaultSocks4CommandResponse(Socks4CommandStatus.SUCCESS)).addListener((ChannelFutureListener) channelFuture -> {
                            if (channelFuture.isSuccess()) {
                                // 在写操作成功的回调中修改Pipeline，此时Channel状态稳定
                                ctx.pipeline().remove(Config.SOCKS_SERVER_PROXY_CLIENT_HANDLER);
                                ctx.pipeline().addLast(Config.SEND_PROXY_MESSAGE_HANDLER, new SendProxyMessageHandler(proxyServerChannel, destAddress, destPort));
                                // connection is ready, enable AutoRead
                                ctx.channel().config().setAutoRead(true);
                                log.info("【Socks4代理】【目标->{}:{}】连接代理服务器成功", destAddress, destPort);
                            } else {
                                log.error("【Socks4代理】【目标->{}:{}】无法回写建立代理连接成功响应", destAddress, destPort);
                                ProxyConnectManager.returnProxyConnect(proxyServerChannel);
                                ctx.close();
                            }
                        });
                    });
                }

                @Override
                public void error(Channel proxyServerChannel) {
                    ctx.channel().eventLoop().execute(() -> {
                        log.error("【Socks4代理】【目标->{}:{}】连接代理服务器失败", destAddress, destPort);
                        ctx
                                .writeAndFlush(new DefaultSocks4CommandResponse(Socks4CommandStatus.REJECTED_OR_FAILED))
                                .addListener(ChannelFutureListener.CLOSE);
                        ProxyConnectManager.returnProxyConnect(proxyServerChannel);
                    });
                }
            }, localChannel, proxyRequest);
        } else if (socksMessage instanceof Socks5InitialRequest) {
            //Socks5InitialRequest处理成功之后，在SocksServerConnectHandler之前添加Socks5CommandRequestDecoder
            //第一个参数是基准handler名称，第二、三参数一组代表添加的名称、新handler
            localChannel.pipeline().addBefore(Config.SOCKS_SERVER_PROXY_CLIENT_HANDLER, Config.SOCKS5_COMMAND_REQUEST_DECODER, new Socks5CommandRequestDecoder());
            DefaultSocks5InitialResponse response = new DefaultSocks5InitialResponse(Socks5AuthMethod.NO_AUTH);
            ctx.writeAndFlush(response).addListener((ChannelFutureListener) channelFuture -> {
                if (channelFuture.isSuccess()) {
                    log.info("【Socks5代理】处理Socks5InitialRequest成功");
                }
            });
        } else if (socksMessage instanceof Socks5CommandRequest) {

            Socks5CommandRequest request = (Socks5CommandRequest) socksMessage;
            String destAddress = request.dstAddr();
            int destPort = request.dstPort();
            //设置代理信息
            ProxyRequest proxyRequest = new ProxyRequest();
            proxyRequest.setTargetHost(destAddress);
            proxyRequest.setTargetPort(destPort);

            ProxyConnectManager.getProxyConnect(new ConnectCallBack() {
                @Override
                public void success(final Channel proxyServerChannel) {
                    // 修复：确保在Channel所属的EventLoop线程中执行Pipeline操作
                    ctx.channel().eventLoop().execute(() -> {
                        if (!ctx.channel().isActive()) {
                            log.warn("【Socks5代理】【目标->{}:{}】本地通道已关闭", destAddress, destPort);
                            ProxyConnectManager.returnProxyConnect(proxyServerChannel);
                            return;
                        }
                        //发送建立连接请求
                        ctx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, request.dstAddrType())).addListener((ChannelFutureListener) channelFuture -> {
                            if (channelFuture.isSuccess()) {
                                ctx.pipeline().remove(Config.SOCKS_SERVER_PROXY_CLIENT_HANDLER);
                                ctx.pipeline().addLast(Config.SEND_PROXY_MESSAGE_HANDLER, new SendProxyMessageHandler(proxyServerChannel, destAddress, destPort));
                                // connection is ready, enable AutoRead
                                ctx.channel().config().setAutoRead(true);
                                log.info("【Socks5代理】【目标->{}:{}】连接代理服务器成功", destAddress, destPort);
                            } else {
                                log.error("【Socks5代理】【目标->{}:{}】无法回写建立代理连接成功响应", destAddress, destPort);
                                ProxyConnectManager.returnProxyConnect(proxyServerChannel);
                                ctx.close();
                            }
                        });
                    });
                }

                @Override
                public void error(Channel proxyServerChannel) {
                    ctx.channel().eventLoop().execute(() -> {
                        log.error("【Socks5代理】【目标->{}:{}】连接代理服务器失败", destAddress, destPort);
                        ctx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.FAILURE, request.dstAddrType()))
                                .addListener(ChannelFutureListener.CLOSE);
                        ProxyConnectManager.returnProxyConnect(proxyServerChannel);
                    });
                }
            }, localChannel, proxyRequest);
        } else {
            log.error("【Socks代理】异常socks类型");
            ctx.close();
        }
    }
}