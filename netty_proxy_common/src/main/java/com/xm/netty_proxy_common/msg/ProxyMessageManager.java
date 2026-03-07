package com.xm.netty_proxy_common.msg;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.CharsetUtil;

/**
 * 代理消息管理工具
 * 优化点：全面采用 ByteBuf 架构，支持零拷贝，并统一引用计数管理
 */
public class ProxyMessageManager {

    private final String username;
    private final String password;

    // 预分配一个不可释放的空 Buffer，用于没有 Data 体的消息，减少 GC 开销
    private static final ByteBuf EMPTY_DATA = Unpooled.unreleasableBuffer(Unpooled.EMPTY_BUFFER);

    public ProxyMessageManager(String username, String password) {
        this.username = username;
        this.password = password;
    }

    /**
     * 内部通用构建方法
     */
    private ProxyMessage buildBaseMessage(byte type, String host, int port, ByteBuf data) {
        ProxyMessage proxyMessage = new ProxyMessage();
        proxyMessage.setType(type);
        proxyMessage.setUsername(username);
        proxyMessage.setPassword(password);
        proxyMessage.setTargetHost(host);
        proxyMessage.setTargetPort(port);
        // 设置数据体（注意：此处不增加引用计数，由调用方决定是否需要 retain）
        proxyMessage.setData(data);
        return proxyMessage;
    }

    /**
     * 服务端连接目标失败消息
     */
    public ProxyMessage wrapServerProxyTargetFail(String host, int port) {
        return buildBaseMessage(ProxyMessageType.SERVER_PROXY_TARGET_FAIL, host, port,
                Unpooled.copiedBuffer("fail", CharsetUtil.UTF_8));
    }

    /**
     * 服务端目标连接关闭消息
     */
    public ProxyMessage wrapServerProxyTargetClose() {
        return buildBaseMessage(ProxyMessageType.SERVER_PROXY_TARGET_CLOSE, "close", 0,
                Unpooled.copiedBuffer("close", CharsetUtil.UTF_8));
    }

    /**
     * 客户端通知服务端关闭消息
     */
    public ProxyMessage wrapClientNotifyServerClose() {
        return buildBaseMessage(ProxyMessageType.CLIENT_NOTIFY_SERVER_CLOSE, "close", 0,
                Unpooled.copiedBuffer("close", CharsetUtil.UTF_8));
    }

    /**
     * 心跳消息
     */
    public ProxyMessage wrapPing() {
        return buildBaseMessage(ProxyMessageType.HEART_BEAT, "ping", 0,
                Unpooled.copiedBuffer("0", CharsetUtil.UTF_8));
    }

    /**
     * 建立连接成功响应
     */
    public ProxyMessage wrapBuildConnectSuccess(String host, int port) {
        return buildBaseMessage(ProxyMessageType.BUILD_CONNECT_SUCCESS, host, port,
                Unpooled.copiedBuffer("success", CharsetUtil.UTF_8));
    }

    /**
     * 建立连接请求
     */
    public ProxyMessage wrapBuildConnect(String host, int port) {
        // 使用 EMPTY_DATA 的副本，不占用额外内存
        return buildBaseMessage(ProxyMessageType.BUILD_CONNECT, host, port, EMPTY_DATA.duplicate());
    }

    /**
     * 核心优化：传输数据时的零拷贝封装
     * *
     * * @param byteBuf 来自于 Netty 管道（从 Socket 直接读取）的直接内存或堆内存 Buffer
     * @return 封装好的 ProxyMessage
     */
    public ProxyMessage wrapTransferByteBuf(ByteBuf byteBuf) {
        ProxyMessage proxyMessage = new ProxyMessage();
        proxyMessage.setType(ProxyMessageType.TRANSFER);
        proxyMessage.setUsername(username);
        proxyMessage.setPassword(password);
        // 传输阶段不需要 Host/Port，设置为空减少 Encoder 序列化开销
        proxyMessage.setTargetHost("");
        proxyMessage.setTargetPort(0);

        // 【零拷贝核心逻辑】
        // 1. 不执行 byteBuf.readBytes(byte[]) 拷贝
        // 2. 调用 retain() 增加引用计数。
        //    原因：调用方（如 ClientSendMessageHandler）在发送完消息后，
        //    SimpleChannelInboundHandler 会尝试释放原始 ByteBuf。
        //    此处 retain 确保数据在异步写入 TCP 发送缓冲区之前一直有效。
        proxyMessage.setData(byteBuf.retain());

        return proxyMessage;
    }
}