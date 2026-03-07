package com.xm.netty_proxy_common.encoder;

import com.xm.netty_proxy_common.msg.ProxyMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import java.nio.charset.StandardCharsets;

public class ProxyMessageEncoder extends MessageToByteEncoder<ProxyMessage> {

    @Override
    protected void encode(ChannelHandlerContext ctx, ProxyMessage msg, ByteBuf out) {
        byte[] hostBytes = msg.getTargetHost().getBytes(StandardCharsets.UTF_8);
        byte[] userBytes = msg.getUsername().getBytes(StandardCharsets.UTF_8);
        byte[] passBytes = msg.getPassword().getBytes(StandardCharsets.UTF_8);

        ByteBuf data = msg.getData();
        int dataLen = (data != null) ? data.readableBytes() : 0;

        // 计算总长度
        int totalLength = 1 + 4 + (4 + hostBytes.length) + (4 + userBytes.length) + (4 + passBytes.length) + 4 + dataLen;

        out.writeInt(totalLength);
        out.writeByte(msg.getType());
        out.writeInt(msg.getTargetPort());

        out.writeInt(hostBytes.length);
        out.writeBytes(hostBytes);

        out.writeInt(userBytes.length);
        out.writeBytes(userBytes);

        out.writeInt(passBytes.length);
        out.writeBytes(passBytes);

        out.writeInt(dataLen);
        if (dataLen > 0) {
            // 直接将 data 写入 out，不产生拷贝
            out.writeBytes(data, data.readerIndex(), dataLen);
        }
    }
}