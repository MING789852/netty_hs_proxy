package com.xm.netty_proxy_client.localHandler;

import com.xm.netty_proxy_client.config.ClientConfig;
import com.xm.netty_proxy_common.utils.DecodeUtil;
import com.xm.netty_proxy_common.utils.params.HttpRequestInfo;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.socksx.v4.Socks4ServerDecoder;
import io.netty.handler.codec.socksx.v4.Socks4ServerEncoder;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class XUnificationServerHandler extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 1) return;

        int readerIndex = in.readerIndex();
        byte firstByte = in.getByte(readerIndex);
        ChannelPipeline p = ctx.pipeline();

        // 1. 检测 SOCKS (0x04=SOCKS4, 0x05=SOCKS5)
        if (firstByte == 0x04 || firstByte == 0x05) {
            if (firstByte == 0x04) {
                p.addAfter(ctx.name(), null, Socks4ServerEncoder.INSTANCE);
                p.addAfter(ctx.name(), null, new Socks4ServerDecoder());
            } else {
                p.addAfter(ctx.name(), null, Socks5ServerEncoder.DEFAULT);
                p.addAfter(ctx.name(), null, new Socks5InitialRequestDecoder());
            }
            log.debug("【协议检测】识别为 SOCKS{} 协议", firstByte);
            p.addLast(ClientConfig.SOCKS_SERVER_PROXY_CLIENT_HANDLER, new SocksServerProxyClientHandler());
            p.remove(this);
            return;
        }

        // 2. 检测 HTTP (利用只读模式的 DecodeUtil)
        HttpRequestInfo httpInfo = DecodeUtil.detectAndParseHttpRequest(in);
        if (httpInfo != null) {
            log.debug("【协议检测】识别为 HTTP 代理, 目标: {}:{}", httpInfo.getTargetHost(), httpInfo.getTargetPort());
            p.addLast(ClientConfig.HTTP_SERVER_PROXY_CLIENT_HANDLER, new HttpServerProxyClientHandler(
                    httpInfo.getTargetHost(), httpInfo.getTargetPort(), httpInfo.getMethod()
            ));
            p.remove(this);
        } else if (in.readableBytes() > 2048) {
            log.error("【协议检测】无法识别协议且数据超限，关闭连接");
            ctx.close();
        }
    }
}