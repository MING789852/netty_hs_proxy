package com.xm.netty_proxy_common.msg;

import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCounted;
import lombok.Data;

@Data
public class ProxyMessage implements ReferenceCounted {
    private byte type;
    private String targetHost;
    private int targetPort;
    private String username;
    private String password;

    // 零拷贝核心：使用 ByteBuf 替代 byte[]
    private ByteBuf data;

    @Override
    public int refCnt() {
        return data != null ? data.refCnt() : 0;
    }

    @Override
    public ProxyMessage retain() {
        if (data != null) data.retain();
        return this;
    }

    @Override
    public ProxyMessage retain(int increment) {
        if (data != null) data.retain(increment);
        return this;
    }

    @Override
    public ProxyMessage touch() {
        if (data != null) data.touch();
        return this;
    }

    @Override
    public ProxyMessage touch(Object hint) {
        if (data != null) data.touch(hint);
        return this;
    }

    @Override
    public boolean release() {
        return data != null && data.release();
    }

    @Override
    public boolean release(int decrement) {
        return data != null && data.release(decrement);
    }
}