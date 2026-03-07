package com.xm.netty_proxy_client.config;

import java.util.ResourceBundle;

public class ClientConfig {
    public static final String HTTP_SERVER_PROXY_CLIENT_HANDLER = "HttpServerProxyClientHandler";
    public static final String SEND_PROXY_MESSAGE_HANDLER = "SendProxyMessageHandler";
    public static final String PROXY_SERVER_MESSAGE_HANDLER = "ProxyServerMessageHandler";
    public static final String SOCKS_SERVER_PROXY_CLIENT_HANDLER ="SocksServerProxyClientHandler";
    public static final String SOCKS5_COMMAND_REQUEST_DECODER ="Socks5CommandRequestDecoder";

    //代理客户端连接代理服务器账号密码
    public static final String USERNAME;
    public static final String PASSWORD;

    //代理客户端连接代理服务器信息
    public static final String SERVER_HOST;
    public static final int SERVER_PORT;
    public static final int PROXY_CONNECT_TIMEOUT;

    //代理客户端信息
    public static final int CLIENT_PORT;

    //代理客户端连接池信息
    public static final int CLIENT_POOL_SIZE;



    static {
        ResourceBundle bundle = ResourceBundle.getBundle("application");
        USERNAME = bundle.getString("username");
        PASSWORD = bundle.getString("password");
        SERVER_HOST = bundle.getString("server.host");
        SERVER_PORT = Integer.parseInt(bundle.getString("server.port"));
        CLIENT_PORT =Integer.parseInt(bundle.getString("client.port"));
        PROXY_CONNECT_TIMEOUT = Integer.parseInt(bundle.getString("client.proxyConnectTimeout"));
        CLIENT_POOL_SIZE = Integer.parseInt(bundle.getString("client.clientPoolSize"));
    }
}
