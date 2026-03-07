package com.xm.netty_proxy_common.utils;

import com.xm.netty_proxy_common.utils.params.HttpRequestInfo;
import io.netty.buffer.ByteBuf;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

@Slf4j
public class DecodeUtil {

    // 限制嗅探深度，防止恶意长包攻击
    private static final int MAX_LOOKAHEAD = 2048;

    public static HttpRequestInfo detectAndParseHttpRequest(ByteBuf buf) {
        int start = buf.readerIndex();
        int limit = Math.min(buf.readableBytes(), MAX_LOOKAHEAD);

        // 1. 寻找第一行结束符
        int eol = findEndOfLine(buf, start, limit);
        if (eol == -1) return null;

        // 2. 只把第一行转为字符串解析方法和URI
        String firstLine = buf.toString(start, eol - start, StandardCharsets.US_ASCII);
        String[] parts = firstLine.split(" ");
        if (parts.length < 3) return null;

        String method = parts[0];
        String uri = parts[1];
        String version = parts[2];
        boolean isConnect = "CONNECT".equalsIgnoreCase(method);

        // 3. 提取 Host 和 Port
        String host;
        int port;

        if (isConnect) {
            // CONNECT 目标就在 URI 里
            HostAndPort hp = parseHostAndPort(uri, 443);
            host = hp.host;
            port = hp.port;
        } else {
            // 普通请求寻找 Host 头部
            String hostHeader = findHeader(buf, start, limit, "Host:");
            if (hostHeader != null) {
                HostAndPort hp = parseHostAndPort(hostHeader, 80);
                host = hp.host;
                port = hp.port;
            } else {
                // 回退方案：从绝对路径 URI 提取
                host = extractHostFromUri(uri);
                port = 80;
            }
        }

        if (host == null) return null;
        return new HttpRequestInfo(method, host, port, isConnect, version, uri);
    }

    private static String extractHostFromUri(String uri) {
        if (uri == null || !uri.contains("://")) {
            return null;
        }
        // 示例: http://example.com:8080/path -> example.com:8080
        int start = uri.indexOf("://") + 3;
        int end = uri.indexOf("/", start);
        if (end == -1) {
            return uri.substring(start);
        }
        return uri.substring(start, end);
    }

    private static int findEndOfLine(ByteBuf buf, int start, int limit) {
        for (int i = start; i < start + limit - 1; i++) {
            if (buf.getByte(i) == '\r' && buf.getByte(i + 1) == '\n') return i;
        }
        return -1;
    }

    private static String findHeader(ByteBuf buf, int start, int limit, String headerName) {
        String content = buf.toString(start, limit, StandardCharsets.US_ASCII);
        for (String line : content.split("\r\n")) {
            if (line.regionMatches(true, 0, headerName, 0, headerName.length())) {
                return line.substring(headerName.length()).trim();
            }
        }
        return null;
    }

    private static HostAndPort parseHostAndPort(String str, int defaultPort) {
        if (str.startsWith("[")) { // IPv6
            int closingBracket = str.indexOf(']');
            String host = str.substring(1, closingBracket);
            int portSep = str.indexOf(':', closingBracket);
            return new HostAndPort(host, portSep != -1 ? Integer.parseInt(str.substring(portSep + 1)) : defaultPort);
        } else {
            int portSep = str.lastIndexOf(':');
            if (portSep != -1 && portSep > str.lastIndexOf(']')) {
                return new HostAndPort(str.substring(0, portSep), Integer.parseInt(str.substring(portSep + 1)));
            }
            return new HostAndPort(str, defaultPort);
        }
    }

    @AllArgsConstructor
    private static class HostAndPort {
        String host;
        int port;
    }
}