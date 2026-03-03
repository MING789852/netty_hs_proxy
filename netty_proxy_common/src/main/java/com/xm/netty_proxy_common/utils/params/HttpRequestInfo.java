package com.xm.netty_proxy_common.utils.params;

import lombok.Data;

@Data
public class HttpRequestInfo {
    private final String method;
    private final String targetHost;
    private final int targetPort;
    private final boolean isConnect;
    private final String version;
    private final String uri;
}
