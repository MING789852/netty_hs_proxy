package com.xm.netty_proxy_server.config;

import java.util.ResourceBundle;

public class ServerConfig {
    public static final String USERNAME;
    public static final String PASSWORD;

    public static final int SERVER_PORT;

    static {
        ResourceBundle bundle = ResourceBundle.getBundle("application");
        USERNAME = bundle.getString("username");
        PASSWORD = bundle.getString("password");
        SERVER_PORT = Integer.parseInt(bundle.getString("server.port"));
    }
}
