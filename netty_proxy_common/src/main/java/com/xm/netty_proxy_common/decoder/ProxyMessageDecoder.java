package com.xm.netty_proxy_common.decoder;

import com.xm.netty_proxy_common.msg.ProxyMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class ProxyMessageDecoder extends MessageToMessageDecoder<ByteBuf> {

    protected static Logger logger= LoggerFactory.getLogger(ProxyMessageDecoder.class);

    @Override
    protected void decode(ChannelHandlerContext channelHandlerContext, ByteBuf byteBuf, List<Object> list) throws Exception {
        if(byteBuf.readableBytes() < 4){
            return;
        }
        int length=byteBuf.readInt();
        if (byteBuf.readableBytes()<length){
            return;
        }

        ProxyMessage msg=new ProxyMessage();
        //获取类型
        byte type=byteBuf.readByte();
        msg.setType(type);
        //获取端口
        int targetPort=byteBuf.readInt();
        msg.setTargetPort(targetPort);
        //获取地址
        int targetHostLength=byteBuf.readInt();
        byte[] targetHostBytes=new byte[targetHostLength];
        byteBuf.readBytes(targetHostBytes);
        String targetHost=new String(targetHostBytes);
        msg.setTargetHost(targetHost);
        //获取用户名
        int usernameLength=byteBuf.readInt();
        byte[] usernameBytes=new byte[usernameLength];
        byteBuf.readBytes(usernameBytes);
        String username=new String(usernameBytes);
        msg.setUsername(username);
        //获取密码
        int passwordLength=byteBuf.readInt();
        byte[] passwordBytes=new byte[passwordLength];
        byteBuf.readBytes(passwordBytes);
        String password=new String(passwordBytes);
        msg.setPassword(password);
        //获取数据
        int dataLength=byteBuf.readInt();
        byte[] data=new byte[dataLength];
        byteBuf.readBytes(data);
        msg.setData(data);

        list.add(msg);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        super.exceptionCaught(ctx, cause);
        logger.error("错误->{}",cause.getMessage());
    }
}
