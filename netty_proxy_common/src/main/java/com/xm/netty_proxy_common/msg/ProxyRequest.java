package com.xm.netty_proxy_common.msg;

import lombok.Data;

@Data
public class ProxyRequest {

    private String targetHost;

    private int targetPort;
}
