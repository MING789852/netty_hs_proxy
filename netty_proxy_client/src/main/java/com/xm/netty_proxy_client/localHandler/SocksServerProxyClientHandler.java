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
    protected void channelRead0(ChannelHandlerContext channelHandlerContext, SocksMessage socksMessage) {
        Channel localChannel=channelHandlerContext.channel();
        if (socksMessage instanceof Socks4CommandRequest) {
            Socks4CommandRequest request= (Socks4CommandRequest) socksMessage;
            String destAddress = request.dstAddr();
            int destPort= request.dstPort();
            log.info("[本地连接][socks4]目标->{}:{}",destAddress,destPort);
            // disable AutoRead until remote connection is ready
            localChannel.config().setAutoRead(false);

            //设置代理信息
            ProxyRequest proxyRequest=new ProxyRequest();
            proxyRequest.setTargetHost(destAddress);
            proxyRequest.setTargetPort(destPort);

            ProxyConnectManager.getProxyConnect(new ConnectCallBack() {
                @Override
                public void success(Channel proxyServerChannel,boolean isPoolChannel) {
                    localChannel.writeAndFlush(new DefaultSocks4CommandResponse(Socks4CommandStatus.SUCCESS)).addListener((ChannelFutureListener) channelFuture -> {
                        if (channelFuture.isSuccess()){
                            localChannel.pipeline().remove(Config.SocksServerProxyClientHandler);
                            localChannel.pipeline().addLast(Config.SendProxyMessageHandler,new SendProxyMessageHandler(proxyServerChannel));
                            // connection is ready, enable AutoRead
                            localChannel.config().setAutoRead(true);
                            log.info("[本地连接][socks4]连接代理服务器成功");
                        }else {
                            log.error("[本地连接][socks4]连接代理服务器失败，无法回写建立代理连接成功响应,归还代理连接");
                            ProxyConnectManager.returnProxyConnect(proxyServerChannel);
                        }
                    });
                }
                @Override
                public void error() {
                    log.error("[本地连接][socks4]连接代理服务器失败");
                    localChannel
                            .writeAndFlush(new DefaultSocks4CommandResponse(Socks4CommandStatus.REJECTED_OR_FAILED));
                }
            },localChannel,proxyRequest);
        }else if (socksMessage instanceof Socks5InitialRequest) {
            //Socks5InitialRequest处理成功之后，在SocksServerConnectHandler之前添加Socks5CommandRequestDecoder
            //第一个参数是基准handler名称，第二、三参数一组代表添加的名称、新handler
            localChannel.pipeline().addBefore(Config.SocksServerProxyClientHandler,Config.Socks5CommandRequestDecoder,new Socks5CommandRequestDecoder());
            DefaultSocks5InitialResponse response=new DefaultSocks5InitialResponse(Socks5AuthMethod.NO_AUTH);
            channelHandlerContext.writeAndFlush(response).addListener((ChannelFutureListener) channelFuture -> {
               if (channelFuture.isSuccess()){
                   log.info("[本地连接][socks5]处理Socks5InitialRequest成功");
               }
            });
        }else if (socksMessage instanceof Socks5CommandRequest){

            Socks5CommandRequest request = (Socks5CommandRequest) socksMessage;
            String destAddress = request.dstAddr();
            int destPort= request.dstPort();
            log.info("[本地连接][socks5]目标->{}:{}",destAddress,destPort);

            //设置代理信息
            ProxyRequest proxyRequest=new ProxyRequest();
            proxyRequest.setTargetHost(destAddress);
            proxyRequest.setTargetPort(destPort);

            ProxyConnectManager.getProxyConnect(new ConnectCallBack() {
                @Override
                public void success(Channel proxyServerChannel,boolean isPoolChannel) {
                    //发送建立连接请求
                    localChannel.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS,request.dstAddrType())).addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(ChannelFuture channelFuture) {
                            if (channelFuture.isSuccess()){
                                localChannel.pipeline().remove(Config.SocksServerProxyClientHandler);
                                localChannel.pipeline().addLast(Config.SendProxyMessageHandler,new SendProxyMessageHandler(proxyServerChannel));
                                // connection is ready, enable AutoRead
                                localChannel.config().setAutoRead(true);
                                log.info("[本地连接][socks5]连接代理服务器成功");
                            }else {
                                log.error("[本地连接][socks5]连接代理服务器失败，无法回写建立代理连接成功响应,归还代理连接");
                                ProxyConnectManager.returnProxyConnect(proxyServerChannel);
                            }
                        }
                    });
                }
                @Override
                public void error() {
                    log.error("[本地连接][socks5]连接代理服务器失败");
                    localChannel
                            .writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.FAILURE,request.dstAddrType()));
                }
            },localChannel,proxyRequest);
        }else {
            log.error("[本地连接]异常socks类型");
            channelHandlerContext.fireChannelRead(socksMessage);
        }
    }
}
