package com.xm.netty_proxy_client.localHandler;

import com.xm.netty_proxy_client.config.Config;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.socksx.SocksPortUnificationServerHandler;
import io.netty.handler.codec.socksx.SocksVersion;
import io.netty.handler.codec.socksx.v4.Socks4ServerDecoder;
import io.netty.handler.codec.socksx.v4.Socks4ServerEncoder;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class XUnificationServerHandler extends SocksPortUnificationServerHandler {
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        int readerIndex = in.readerIndex();
        if (in.writerIndex() != readerIndex) {
            ChannelPipeline p = ctx.pipeline();
            byte versionVal = in.getByte(readerIndex);
            SocksVersion version = SocksVersion.valueOf(versionVal);
            switch (version) {
                case SOCKS4a:
                    log.debug("{} Protocol version: {}", ctx.channel(), version);
                    p.addAfter(ctx.name(), null, Socks4ServerEncoder.INSTANCE);
                    p.addAfter(ctx.name(), null, new Socks4ServerDecoder());
                    p.addLast(Config.SocksServerProxyClientHandler,new SocksServerProxyClientHandler());
                    break;
                case SOCKS5:
                    log.debug("{} Protocol version: {}", ctx.channel(), version);
                    p.addAfter(ctx.name(), null, Socks5ServerEncoder.DEFAULT);
                    p.addAfter(ctx.name(), null, new Socks5InitialRequestDecoder());
                    p.addLast(Config.SocksServerProxyClientHandler,new SocksServerProxyClientHandler());
                    break;
                default:
                    p.addLast(new HttpRequestDecoder(),
                            new HttpResponseEncoder(),
                            new HttpObjectAggregator(3 * 1024 * 1024),
                            new HttpServerProxyClientHandler());
                    in.retain();
                    in.readerIndex(0);
                    ctx.fireChannelRead(in);
                    break;
            }
            p.remove(this);
        }
    }
}
