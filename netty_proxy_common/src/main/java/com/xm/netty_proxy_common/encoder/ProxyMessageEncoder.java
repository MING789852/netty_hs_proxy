package com.xm.netty_proxy_common.encoder;

import com.xm.netty_proxy_common.msg.ProxyMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

public class ProxyMessageEncoder extends MessageToByteEncoder<ProxyMessage> {

    @Override
    protected void encode(ChannelHandlerContext channelHandlerContext, ProxyMessage proxyMessage, ByteBuf byteBuf) throws Exception {
        byte[] targetHostByte=proxyMessage.getTargetHost().getBytes();
        int targetPortLength=4;
        int typeLength=1;

        byte[] usernameByte=proxyMessage.getUsername().getBytes();
        byte[] passwordByte=proxyMessage.getPassword().getBytes();
        byte[] data=proxyMessage.getData();
        //总长度
        int length=typeLength+targetPortLength+
                4+targetHostByte.length+
                4+usernameByte.length+
                4+passwordByte.length+
                4+data.length;
        byteBuf.writeInt(length);
        //设置类型
        byteBuf.writeByte(proxyMessage.getType());
        //设置目标端口
        byteBuf.writeInt(proxyMessage.getTargetPort());
        //设置目标地址
        byteBuf.writeInt(targetHostByte.length);
        byteBuf.writeBytes(targetHostByte);
        //设置用户名
        byteBuf.writeInt(usernameByte.length);
        byteBuf.writeBytes(usernameByte);
        //设置密码
        byteBuf.writeInt(passwordByte.length);
        byteBuf.writeBytes(passwordByte);
        //设置数据
        byteBuf.writeInt(data.length);
        byteBuf.writeBytes(data);
    }


}
