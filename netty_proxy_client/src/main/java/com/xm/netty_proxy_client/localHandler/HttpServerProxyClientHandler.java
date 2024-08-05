package com.xm.netty_proxy_client.localHandler;

import com.xm.netty_proxy_client.config.Config;
import com.xm.netty_proxy_client.manager.ProxyConnectManager;
import com.xm.netty_proxy_client.proxyHandler.SendProxyMessageHandler;
import com.xm.netty_proxy_common.callback.ConnectCallBack;
import com.xm.netty_proxy_common.msg.ProxyRequest;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class HttpServerProxyClientHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    @Override
    protected void channelRead0(ChannelHandlerContext channelHandlerContext, FullHttpRequest httpRequest) {
        if (httpRequest.decoderResult().isSuccess()){
            Channel localChannel = channelHandlerContext.channel();

            boolean isConnectMethod = HttpMethod.CONNECT.equals(httpRequest.method());

            if (!isConnectMethod){
//                        log.info("[http代理客户端]method->{},refCnt->{},非建立连接请求,本请求直接转发",httpRequest.method().name(),httpRequest.refCnt());
//                        ByteBuf content =httpRequest.content();
//                        proxyServerChannel.writeAndFlush(ProxyConnectManager.wrapTransferByteBuf(content));
                localChannel.close();
                return;
            }

            // 解析目标主机host和端口号
            HostAndPort hostAndPort = parseHostAndPort(httpRequest, isConnectMethod);
            log.info("[http代理客户端]代理目标->{}",hostAndPort);
            // disable AutoRead until remote connection is ready
            localChannel.config().setAutoRead(false);

            //设置代理信息
            ProxyRequest proxyRequest=new ProxyRequest();
            proxyRequest.setTargetHost(hostAndPort.getHost());
            proxyRequest.setTargetPort(hostAndPort.getPort());

            ProxyConnectManager.getProxyConnect(new ConnectCallBack() {
                @Override
                public void success(Channel proxyServerChannel,boolean isPoolChannel) {
                    // CONNECT 请求回复连接建立成功
                    HttpResponse connectedResponse = new DefaultHttpResponse(httpRequest.protocolVersion(), new HttpResponseStatus(200, "Connection Established"));
                    localChannel.writeAndFlush(connectedResponse).addListener((ChannelFutureListener) channelFuture -> {
                        if (channelFuture.isSuccess()){
                            localChannel.pipeline().remove(HttpRequestDecoder.class);
                            localChannel.pipeline().remove(HttpResponseEncoder.class);
                            localChannel.pipeline().remove(HttpObjectAggregator.class);
                            localChannel.pipeline().remove(HttpServerProxyClientHandler.this);
                            localChannel.pipeline().addLast(Config.SendProxyMessageHandler,new SendProxyMessageHandler(proxyServerChannel));

                            // connection is ready, enable AutoRead
                            localChannel.config().setAutoRead(true);

                            log.info("[http代理客户端]连接代理服务器成功");
                        }else {
                            log.error("[http代理客户端]连接代理服务器失败，无法回写建立代理连接成功响应,归还代理连接");
                            ProxyConnectManager.returnProxyConnect(proxyServerChannel);
                        }
                    });
                }
                @Override
                public void error() {
                    log.error("[http代理客户端]连接代理服务器失败");
                }
            },localChannel,proxyRequest);
        }else {
            log.error("[http代理客户端]解析FullHttpRequest失败");
        }
    }

    /**
     * 解析header信息，建立连接
     * HTTP 请求头如下
     * GET http://www.baidu.com/ HTTP/1.1
     * Host: www.baidu.com
     * User-Agent: curl/7.69.1
     * Proxy-Connection:Keep-Alive
     * ---------------------------
     * HTTPS请求头如下
     * CONNECT www.baidu.com:443 HTTP/1.1
     * Host: www.baidu.com:443
     * User-Agent: curl/7.69.1
     * Proxy-Connection: Keep-Alive
     */
    private HostAndPort parseHostAndPort(HttpRequest httpRequest,boolean isConnectMethod) {
        String hostAndPortStr;
        if (isConnectMethod) {
            // CONNECT 请求以请求行为准
            hostAndPortStr = httpRequest.uri();
        } else {
            hostAndPortStr = httpRequest.headers().get("Host");
        }
        String[] hostPortArray = hostAndPortStr.split(":");
        String host = hostPortArray[0];
        int port;
        if (hostPortArray.length == 2) {
            port = Integer.parseInt(hostPortArray[1]);
        } else if (isConnectMethod) {
            // 没有端口号，CONNECT 请求默认443端口
            port = 443;
        } else {
            // 没有端口号，普通HTTP请求默认80端口
            port = 80;
        }
        return new HostAndPort(host,port);
    }

    @Data
    static class HostAndPort{
        private String host;
        private int port;
        public HostAndPort(String host, int port) {
            this.host = host;
            this.port = port;
        }

        @Override
        public String toString() {
            return "HostAndPort{" +
                    "host='" + host + '\'' +
                    ", port=" + port +
                    '}';
        }
    }
}
