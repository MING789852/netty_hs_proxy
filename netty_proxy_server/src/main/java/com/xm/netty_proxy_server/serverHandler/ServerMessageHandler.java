package com.xm.netty_proxy_server.serverHandler;

import com.xm.netty_proxy_common.callback.ConnectCallBack;
import com.xm.netty_proxy_common.key.Constants;
import com.xm.netty_proxy_common.msg.ProxyMessage;
import com.xm.netty_proxy_common.msg.ProxyMessageType;
import com.xm.netty_proxy_server.config.Config;
import com.xm.netty_proxy_server.manager.ProxyConnectManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ServerMessageHandler extends SimpleChannelInboundHandler<ProxyMessage> {

    @Override
    protected void channelRead0(ChannelHandlerContext channelHandlerContext, ProxyMessage proxyMessage) {
        Channel serverChannel=channelHandlerContext.channel();
        //验证账号密码是否正确
        if (Config.username.equals(proxyMessage.getUsername())&&Config.password.equals(proxyMessage.getPassword())){
            if (ProxyMessageType.BUILD_CONNECT==proxyMessage.getType()){
                ProxyConnectManager.connect(proxyMessage.getTargetHost(), proxyMessage.getTargetPort(), new ConnectCallBack() {
                    @Override
                    public void success(Channel connectChannel,boolean isPoolChannel) {
                        //绑定连接
                        ProxyConnectManager.bindChannel(serverChannel,connectChannel);
                        //发送连接成功回调
                        serverChannel.writeAndFlush(ProxyConnectManager.getProxyMessageManager().wrapConnectSuccess(proxyMessage.getTargetHost(),proxyMessage.getTargetPort()));
                    }

                    @Override
                    public void error(Channel connectChannel) {
                        //通知客户端关闭连接
                        log.info("[代理服务]通知客户端代理连接->{}:{}失败",proxyMessage.getTargetHost(),proxyMessage.getTargetPort());
                        ProxyConnectManager.notifyServerProxyFail(serverChannel,proxyMessage.getTargetHost(),proxyMessage.getTargetPort());
                    }
                });
            }
            if (ProxyMessageType.TRANSFER==proxyMessage.getType()){
                Channel connectChannel=serverChannel.attr(Constants.NEXT_CHANNEL).get();
                if (connectChannel!=null){
                    ByteBuf byteBuf = channelHandlerContext.alloc().buffer(proxyMessage.getData().length);
                    byteBuf.writeBytes(proxyMessage.getData());
                    log.debug("[代理服务]转发数据到代理目标");
                    connectChannel.writeAndFlush(byteBuf);
                }
            }
            if (ProxyMessageType.NOTIFY_SERVER_CLOSE==proxyMessage.getType()){
                log.info("[代理服务]接收到客户端断开连接请求");
                Channel connectChannel=serverChannel.attr(Constants.NEXT_CHANNEL).get();
                if (connectChannel!=null){
                    connectChannel.flush().close();
                }
                ProxyConnectManager.unBindChannel(serverChannel);
                //通知客户端关闭完成
                serverChannel.writeAndFlush(ProxyConnectManager.getProxyMessageManager().wrapNotifyServerCloseAck());
            }
        }
    }


    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        Channel serverChannel = ctx.channel();
        ProxyConnectManager.unBindChannel(serverChannel);
    }


    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("[代理服务]错误->{}",cause.getMessage());
    }
}
