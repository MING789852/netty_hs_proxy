package com.xm.netty_proxy_client.localHandler;

import com.xm.netty_proxy_client.config.Config;
import com.xm.netty_proxy_common.utils.DecodeUtil;
import com.xm.netty_proxy_common.utils.params.HttpRequestInfo;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.socksx.SocksVersion;
import io.netty.handler.codec.socksx.v4.Socks4ServerDecoder;
import io.netty.handler.codec.socksx.v4.Socks4ServerEncoder;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class XUnificationServerHandler extends SimpleChannelInboundHandler<ByteBuf> {


    // 标记是否已经处理过第一个ByteBuf
    private boolean firstByteBufProcessed = false;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf byteBuf) {
        if (!firstByteBufProcessed) {
            firstByteBufProcessed = true;

            // 禁用自动读取，确保只读取这一次
            ctx.channel().config().setAutoRead(false);

            // 处理协议检测
            processProtocol(ctx, byteBuf);
        } else {
            log.error("【代理区分】非第一次读取");
        }
    }

    private void processProtocol(ChannelHandlerContext ctx, ByteBuf in){
        // 1. 确保有足够字节进行判断
        if (in.readableBytes() < 8) { // 最小需要检测SOCKS版本和HTTP方法
            log.error("【代理区分】处理协议字节数不足");
            return;
        }
        int readerIndex = in.readerIndex();
        ChannelPipeline p = ctx.pipeline();
        // 2. 先尝试判断SOCKS
        byte firstByte = in.getByte(readerIndex);
        try {
            SocksVersion version = SocksVersion.valueOf(firstByte);
            switch (version) {
                case SOCKS4a:
                    log.debug("【代理区分】SOCKS4a detected");
                    p.addAfter(ctx.name(), null, Socks4ServerEncoder.INSTANCE);
                    p.addAfter(ctx.name(), null, new Socks4ServerDecoder());
                    p.addLast(Config.SOCKS_SERVER_PROXY_CLIENT_HANDLER, new SocksServerProxyClientHandler());
                    ctx.fireChannelRead(in.retain());
                    p.remove(this);
                    return;
                case SOCKS5:
                    log.debug("【代理区分】SOCKS5 detected");
                    p.addAfter(ctx.name(), null, Socks5ServerEncoder.DEFAULT);
                    p.addAfter(ctx.name(), null, new Socks5InitialRequestDecoder());
                    p.addLast(Config.SOCKS_SERVER_PROXY_CLIENT_HANDLER, new SocksServerProxyClientHandler());
                    ctx.fireChannelRead(in.retain());
                    p.remove(this);
                    return;
            }
        } catch (IllegalArgumentException e) {
            // 不是SOCKS协议，继续判断
        }
        // 3. 判断是否为HTTP
        HttpRequestInfo httpInfo = DecodeUtil.detectAndParseHttpRequest(in);
        if (httpInfo != null) {
            log.debug("【代理区分】HTTP proxy detected, method: {}, target: {}:{}", httpInfo.getMethod(), httpInfo.getTargetHost(), httpInfo.getTargetPort());
            // 4. 创建并添加优化后的HTTP处理器
            if (p.get(Config.HTTP_SERVER_PROXY_CLIENT_HANDLER)!=null){
                p.remove(Config.HTTP_SERVER_PROXY_CLIENT_HANDLER);
            }
            p.addLast(Config.HTTP_SERVER_PROXY_CLIENT_HANDLER,new HttpServerProxyClientHandler(
                    httpInfo.getTargetHost(),
                    httpInfo.getTargetPort(),
                    httpInfo.getMethod()
            ));
            // 5. 移除当前处理器
            p.remove(this);
            // 6. 重新触发channelRead，让新处理器处理这个原始数据
            ByteBuf firstByteBuf=in.retain();
            ctx.fireChannelRead(firstByteBuf);
        } else {
            // 7. 未知协议，关闭连接
            log.error("【代理区分】Unknown protocol, closing connection");
            ctx.close();
        }
    }
}