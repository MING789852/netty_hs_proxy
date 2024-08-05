package com.xm.netty_proxy_common.decoder;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MLengthFieldBasedFrameDecoder extends LengthFieldBasedFrameDecoder {

    protected static Logger logger= LoggerFactory.getLogger(MLengthFieldBasedFrameDecoder.class);

    public MLengthFieldBasedFrameDecoder() {
        super(Integer.MAX_VALUE, 0, 4);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        super.exceptionCaught(ctx, cause);
        logger.error("错误->{}",cause.getMessage());
    }
}
