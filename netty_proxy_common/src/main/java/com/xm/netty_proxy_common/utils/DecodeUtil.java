package com.xm.netty_proxy_common.utils;

import com.xm.netty_proxy_common.utils.params.HttpRequestInfo;
import io.netty.buffer.ByteBuf;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class DecodeUtil {

    // 主机名模式匹配（包含端口）
    private static final Pattern HOST_PORT_PATTERN = Pattern.compile("^([^:]+)(?::(\\d+))?$");

    // IPv6地址模式匹配
    private static final Pattern IPV6_PATTERN = Pattern.compile("^\\[([0-9a-fA-F:]+)](?::(\\d+))?$");

    // HTTP方法检测模式
    private static final String[] HTTP_METHODS = {
            "GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS", "CONNECT", "PATCH", "TRACE"
    };

    /**
     * 检测并解析HTTP请求
     */
    public static HttpRequestInfo detectAndParseHttpRequest(ByteBuf buf) {
        return detectAndParseHttpRequest(buf, 0);
    }

    /**
     * 检测并解析HTTP请求（指定起始位置）
     */
    public static HttpRequestInfo detectAndParseHttpRequest(ByteBuf buf, int startIdx) {
        try {
            if (buf == null || !buf.isReadable()) {
                return null;
            }

            // 保存原始读指针位置
            int savedReaderIndex = buf.readerIndex();
            buf.readerIndex(startIdx);

            try {
                // 读取一行（请求行）
                String requestLine = readLineLimited(buf);
                if (requestLine == null || requestLine.isEmpty()) {
                    return null;
                }

                // 解析请求行: 方法 URI 版本
                String[] requestParts = requestLine.split(" ", 3);
                if (requestParts.length < 3) {
                    return null; // 无效的请求行
                }

                String method = requestParts[0];
                String uri = requestParts[1];
                String version = requestParts[2];

                // 验证HTTP方法
                if (!isValidHttpMethod(method)) {
                    return null;
                }

                boolean isConnect = "CONNECT".equalsIgnoreCase(method);

                // 解析主机和端口
                HostAndPort hostAndPort = extractHostAndPortFromRequest(buf, method, uri, isConnect);
                if (hostAndPort == null) {
                    log.warn("无法从HTTP请求中解析出目标地址");
                    return null;
                }

                return new HttpRequestInfo(
                        method,
                        hostAndPort.getHost(),
                        hostAndPort.getPort(),
                        isConnect,
                        version,
                        uri
                );

            } finally {
                // 恢复原始读指针位置
                buf.readerIndex(savedReaderIndex);
            }

        } catch (Exception e) {
            log.warn("解析HTTP请求时发生异常", e);
            return null;
        }
    }

    /**
     * 从ByteBuf中读取一行，限制最大长度
     */
    private static String readLineLimited(ByteBuf buf) {
        if (!buf.isReadable()) {
            return null;
        }

        int endIdx = -1;
        int startIdx = buf.readerIndex();
        int searchLimit = buf.readableBytes();
        for (int i = 0; i < searchLimit - 1; i++) { // -1 为了检查 \r\n
            if (buf.getByte(startIdx + i) == '\r' && buf.getByte(startIdx + i + 1) == '\n') {
                endIdx = startIdx + i;
                break;
            }
        }

        if (endIdx == -1) {
            return null; // 没找到行结束符
        }

        byte[] lineBytes = new byte[endIdx - startIdx];
        buf.readBytes(lineBytes);
        buf.skipBytes(2); // 跳过 \r\n
        return new String(lineBytes, StandardCharsets.US_ASCII);
    }

    /**
     * 验证HTTP方法是否有效
     */
    private static boolean isValidHttpMethod(String method) {
        if (method == null || method.isEmpty()) {
            return false;
        }

        for (String httpMethod : HTTP_METHODS) {
            if (httpMethod.equals(method)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 从HTTP请求中提取主机和端口
     */
    private static HostAndPort extractHostAndPortFromRequest(ByteBuf buf, String method,
                                                             String uri, boolean isConnect) {
        if (isConnect) {
            // CONNECT 方法的URI就是 host:port
            return parseHostAndPort(uri, true);
        }

        // 读取并解析Host头
        String hostHeader = extractHostHeaderFromBuffer(buf);
        if (hostHeader != null) {
            return parseHostAndPort(hostHeader, false);
        }

        // 从绝对路径URI中提取
        String hostFromUri = extractHostFromAbsoluteUri(uri);
        if (hostFromUri != null) {
            return parseHostAndPort(hostFromUri, false);
        }

        return null;
    }

    /**
     * 从ByteBuf中提取Host头
     */
    private static String extractHostHeaderFromBuffer(ByteBuf buf) {
        int savedReaderIndex = buf.readerIndex();

        try {
            String line;
            // line==null没有完整行
            // line.isEmpty()遇到空行，请求头结束
            while ((line = readLineLimited(buf)) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("host:")) {
                    return line.substring(5).trim();
                }
            }
        } finally {
            buf.readerIndex(savedReaderIndex);
        }

        return null;
    }

    /**
     * 从绝对路径URI中提取主机
     */
    private static String extractHostFromAbsoluteUri(String uri) {
        if (uri == null || !uri.contains("://")) {
            return null;
        }

        try {
            // 移除协议头
            int protocolEnd = uri.indexOf("://");
            String withoutProtocol = uri.substring(protocolEnd + 3);

            // 提取主机部分
            int pathStart = withoutProtocol.indexOf("/");
            if (pathStart > 0) {
                return withoutProtocol.substring(0, pathStart);
            } else {
                return withoutProtocol;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析主机和端口字符串
     */
    private static HostAndPort parseHostAndPort(String hostAndPortStr, boolean isConnect) {
        if (hostAndPortStr == null || hostAndPortStr.isEmpty()) {
            return null;
        }

        hostAndPortStr = hostAndPortStr.trim();

        // 尝试匹配IPv6地址格式 [2001:db8::1]:8080
        Matcher ipv6Matcher = IPV6_PATTERN.matcher(hostAndPortStr);
        if (ipv6Matcher.matches()) {
            String host = ipv6Matcher.group(1);
            String portStr = ipv6Matcher.group(2);
            int port = getDefaultPort(portStr, isConnect);

            if (isValidPort(port)) {
                return new HostAndPort(host, port);
            }
        }

        // 处理普通主机名格式
        Matcher matcher = HOST_PORT_PATTERN.matcher(hostAndPortStr);
        if (matcher.matches()) {
            String host = matcher.group(1);
            String portStr = matcher.group(2);
            int port = getDefaultPort(portStr, isConnect);

            if (isValidPort(port)) {
                return new HostAndPort(host, port);
            }
        }

        return null;
    }

    /**
     * 获取默认端口
     */
    private static int getDefaultPort(String portStr, boolean isConnect) {
        if (portStr != null && !portStr.isEmpty()) {
            try {
                return Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
                log.warn("Invalid port format: {}", portStr);
            }
        }
        return isConnect ? 443 : 80; // CONNECT默认443，其他默认80
    }

    /**
     * 验证端口是否有效
     */
    private static boolean isValidPort(int port) {
        return port > 0 && port <= 65535;
    }

    /**
     * 主机和端口
     */
    @Data
    private static class HostAndPort {
        private final String host;
        private final int port;
    }

    /**
     * 测试方法
     */
    public static void main(String[] args) {
        // 测试HTTP版本解析
        testHttpVersionParsing();
    }

    private static void testHttpVersionParsing() {
        // 测试完整的HTTP请求解析
//        String httpRequest = "GET http://example.com:8080/path HTTP/1.1\r\n" +
//                "Host: example.com:8080\r\n" +
//                "User-Agent: TestClient\r\n" +
//                "\r\n";

        // 标准HTTPS隧道请求
        String httpRequest = "CONNECT example.com:443 HTTP/1.1\r\n" +
                "Host: example.com:443\r\n" +
                "User-Agent: Mozilla/5.0\r\n" +
                "Proxy-Connection: Keep-Alive\r\n" +
                "\r\n";

        //IPv6地址
//        String httpRequest = "CONNECT [2001:db8::1]:8443 HTTP/1.1\r\n" +
//                "Host: [2001:db8::1]:8443\r\n" +
//                "\r\n";

        ByteBuf buf = io.netty.buffer.Unpooled.copiedBuffer(httpRequest.getBytes(StandardCharsets.US_ASCII));
        HttpRequestInfo info = detectAndParseHttpRequest(buf);

        if (info != null) {
            System.out.println("\n解析结果:");
            System.out.println("Method: " + info.getMethod());
            System.out.println("Version: " + info.getVersion());
            System.out.println("URI: " + info.getUri());
            System.out.println("Host: " + info.getTargetHost());
            System.out.println("Port: " + info.getTargetPort());
            System.out.println("Is CONNECT: " + info.isConnect());
        }
    }
}