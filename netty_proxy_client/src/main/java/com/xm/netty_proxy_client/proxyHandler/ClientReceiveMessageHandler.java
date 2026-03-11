package com.xm.netty_proxy_client.proxyHandler;

import com.xm.netty_proxy_client.manager.ClientProxyConnectManager;
import com.xm.netty_proxy_common.callback.ConnectCallBack;
import com.xm.netty_proxy_common.msg.ProxyMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import static com.xm.netty_proxy_common.msg.ProxyMessageType.*;

@Slf4j
public class ClientReceiveMessageHandler extends ChannelInboundHandlerAdapter {

    private final ConnectCallBack connectCallBack;
    private final Channel localChannel;

    public ClientReceiveMessageHandler(ConnectCallBack connectCallBack, Channel localChannel, String targetHost, int targetPort) {
        this.connectCallBack = connectCallBack;
        this.localChannel = localChannel;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ProxyMessage)) {
            ctx.fireChannelRead(msg);
            return;
        }

        ProxyMessage proxyMessage = (ProxyMessage) msg;
        Channel proxyChannel = ctx.channel();

        try {
            switch (proxyMessage.getType()) {
                case BUILD_CONNECT_SUCCESS:
                    connectCallBack.success(proxyChannel);
                    break;
                case TRANSFER:
                    handleTransferData(proxyChannel, proxyMessage);
                    break;
                case SERVER_PROXY_TARGET_FAIL:
                case SERVER_PROXY_TARGET_CLOSE:
                    if (localChannel != null) localChannel.close();
                    ClientProxyConnectManager.returnProxyConnect(proxyChannel);
                    break;
                default:
                    break;
            }
        } finally {
            // 修复：非 TRANSFER 类型的消息，或者未被转发的数据，必须手动释放防止内存泄漏
            if (proxyMessage.getType() != TRANSFER) {
                if (proxyMessage.getData() != null) {
                    ReferenceCountUtil.release(proxyMessage.getData());
                }
            }
        }
    }

    private void handleTransferData(Channel proxyChannel, ProxyMessage proxyMessage) {
        if (localChannel == null || !localChannel.isActive()) {
            if (proxyMessage.getData() != null) {
                ReferenceCountUtil.release(proxyMessage.getData());
            }
            ClientProxyConnectManager.returnProxyConnect(proxyChannel);
            return;
        }

        ByteBuf data = proxyMessage.getData();
        if (data == null) return;

        // 直接写入，writeAndFlush 底层完成后会自动 release，无需 retain
        localChannel.writeAndFlush(data).addListener(future -> {
            if (!future.isSuccess()) {
                localChannel.close();
                ClientProxyConnectManager.returnProxyConnect(proxyChannel);
            }
        });

        // 背压控制：本地浏览器/应用接收太慢，暂停从代理服务器读取
        if (!localChannel.isWritable()) {
            proxyChannel.config().setAutoRead(false);
        }
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        // 修复死锁：当代理通道恢复可写时，说明代理服务器可以接收请求了，唤醒本地通道
        if (ctx.channel().isWritable() && localChannel != null && localChannel.isActive()) {
            localChannel.config().setAutoRead(true);
        }
        super.channelWritabilityChanged(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("【代理通道】异常: {}", cause.getMessage());
        ClientProxyConnectManager.returnProxyConnect(ctx.channel());
        if (localChannel != null) localChannel.close();
    }
}