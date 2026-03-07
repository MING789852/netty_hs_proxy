package com.xm.netty_proxy_common.decoder;

import com.xm.netty_proxy_common.msg.ProxyMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class ProxyMessageDecoder extends MessageToMessageDecoder<ByteBuf> {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 4) return;

        in.markReaderIndex();
        int length = in.readInt();
        if (in.readableBytes() < length) {
            in.resetReaderIndex();
            return;
        }

        ProxyMessage msg = new ProxyMessage();
        msg.setType(in.readByte());
        msg.setTargetPort(in.readInt());

        // 读取字符串
        msg.setTargetHost(readString(in));
        msg.setUsername(readString(in));
        msg.setPassword(readString(in));

        // 读取数据体
        int dataLen = in.readInt();
        if (dataLen > 0) {
            //使用 readRetainedSlice 引用原始内存并将计数+1，实现零拷贝
            msg.setData(in.readRetainedSlice(dataLen));
        }
        out.add(msg);
    }

    private String readString(ByteBuf in) {
        int len = in.readInt();
        if (len <= 0) return "";
        byte[] bytes = new byte[len];
        in.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}