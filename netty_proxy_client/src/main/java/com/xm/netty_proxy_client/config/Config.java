package com.xm.netty_proxy_client.config;

import java.util.ResourceBundle;

public class Config {
    public static final String SendProxyMessageHandler = "SendProxyMessageHandler";
    public static final String ProxyServerMessageHandler = "ProxyServerMessageHandler";
    public static final String SocksPortUnificationServerHandler="SocksPortUnificationServerHandler";
    public static final String SocksServerProxyClientHandler="SocksServerProxyClientHandler";
    public static final String Socks5CommandRequestDecoder="Socks5CommandRequestDecoder";

    public static final String username;
    public static final String password;

    public static final String serverHost;
    public static final int serverPort;

    public static final int clientPort;

    public static final boolean clientOpenPool;

    public static final int clientPoolSize;

    public static final String proxyType;


    static {
        ResourceBundle bundle = ResourceBundle.getBundle("application");
        username = bundle.getString("username");
        password= bundle.getString("password");
        serverHost= bundle.getString("server.host");
        serverPort= Integer.parseInt(bundle.getString("server.port"));
        clientPort=Integer.parseInt(bundle.getString("client.port"));
        clientOpenPool=Boolean.parseBoolean(bundle.getString("client.openPool"));

        int poolSize =0;
        if (clientOpenPool){
            poolSize = Integer.parseInt(bundle.getString("client.clientPoolSize"));
        }
        clientPoolSize = poolSize;

        proxyType=bundle.getString("server.proxyType");
    }
}
