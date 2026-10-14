package cc.thonly.wikikernel.plugin.aiagent.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SOCKS5 → 本机 HTTP CONNECT 桥。
 * <p>
 * 背景：JDK 的 {@code java.net.http.HttpClient} **只支持 HTTP 代理**，{@code socks5://} 会被忽略
 * （表现为直连、连接超时）。这里在 {@code 127.0.0.1} 的临时端口上开一个只服务本机的迷你 HTTP 代理：
 * 收到 {@code CONNECT host:port} 后，用 SOCKS5（RFC 1928）连到目标，再把两端字节对接。
 * <p>
 * 只处理 HTTPS 的 CONNECT 隧道（各家 AI 端点都是 https；本机 Ollama 之类的 http 端点不需要代理）。
 * 桥只在回环地址监听，不对外暴露。
 */
final class Socks5Bridge {

    private static final Map<String, Socks5Bridge> BRIDGES = new ConcurrentHashMap<>();

    private final ServerSocket server;
    private final String socksHost;
    private final int socksPort;
    private final boolean remoteDns;

    private Socks5Bridge(String socksHost, int socksPort, boolean remoteDns) throws IOException {
        this.socksHost = socksHost;
        this.socksPort = socksPort;
        this.remoteDns = remoteDns;
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(this::acceptLoop, "socks5-bridge-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /**
     * 取（或创建）某个 SOCKS5 代理对应的桥。
     *
     * @param host      SOCKS5 代理主机
     * @param port      SOCKS5 代理端口
     * @param remoteDns true = {@code socks5h}（域名交给代理解析）
     */
    static Socks5Bridge forProxy(String host, int port, boolean remoteDns) throws IOException {
        String key = host + ":" + port + (remoteDns ? "#h" : "");
        Socks5Bridge existing = BRIDGES.get(key);
        if (existing != null) {
            return existing;
        }
        synchronized (BRIDGES) {
            existing = BRIDGES.get(key);
            if (existing != null) {
                return existing;
            }
            Socks5Bridge created = new Socks5Bridge(host, port, remoteDns);
            BRIDGES.put(key, created);
            return created;
        }
    }

    /** 桥监听的本地端口（给 HttpClient 当 HTTP 代理用）。 */
    int port() {
        return server.getLocalPort();
    }

    // ==================== 迷你 HTTP CONNECT 代理 ====================

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                Thread worker = new Thread(() -> handle(client), "socks5-bridge-conn");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException exception) {
                if (server.isClosed()) {
                    return;
                }
            }
        }
    }

    private void handle(Socket client) {
        try (client) {
            client.setSoTimeout(20000);
            String requestLine = readLine(client.getInputStream());
            if (requestLine == null || !requestLine.toUpperCase(Locale.ROOT).startsWith("CONNECT ")) {
                write(client, "HTTP/1.1 501 Not Implemented\r\nContent-Length: 0\r\n\r\n");
                return;
            }
            String target = requestLine.split(" ")[1];
            int colon = target.lastIndexOf(':');
            String host = colon > 0 ? target.substring(0, colon) : target;
            int port = colon > 0 ? Integer.parseInt(target.substring(colon + 1)) : 443;
            String line;
            while ((line = readLine(client.getInputStream())) != null && !line.isEmpty()) {
                // 丢弃请求头
            }
            Socket upstream = new Socket();
            try {
                upstream.connect(new InetSocketAddress(socksHost, socksPort), 15000);
                upstream.setSoTimeout(0);
                handshake(upstream, host, port);
            } catch (IOException exception) {
                try {
                    upstream.close();
                } catch (IOException ignored) {
                    // 忽略
                }
                write(client, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n");
                return;
            }
            write(client, "HTTP/1.1 200 Connection established\r\n\r\n");
            client.setSoTimeout(0);
            pump(client, upstream);
        } catch (IOException | NumberFormatException ignored) {
            // 客户端断开 / 目标不合法：直接结束这条连接
        }
    }

    /** SOCKS5 握手 + CONNECT（RFC 1928）。 */
    private void handshake(Socket socket, String host, int port) throws IOException {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();
        out.write(new byte[]{0x05, 0x01, 0x00}); // 版本 5，一种方法：无认证
        out.flush();
        byte[] greeting = in.readNBytes(2);
        if (greeting.length < 2 || greeting[0] != 0x05 || greeting[1] != 0x00) {
            throw new IOException("SOCKS5 代理要求认证或协议不匹配");
        }
        byte[] address;
        int atyp;
        if (remoteDns) {
            byte[] name = host.getBytes(StandardCharsets.US_ASCII);
            address = new byte[1 + name.length];
            address[0] = (byte) name.length;
            System.arraycopy(name, 0, address, 1, name.length);
            atyp = 0x03;
        } else {
            byte[] ip = InetAddress.getByName(host).getAddress();
            address = ip;
            atyp = ip.length == 4 ? 0x01 : 0x04;
        }
        byte[] request = new byte[4 + address.length + 2];
        request[0] = 0x05;
        request[1] = 0x01;
        request[2] = 0x00;
        request[3] = (byte) atyp;
        System.arraycopy(address, 0, request, 4, address.length);
        request[4 + address.length] = (byte) (port >> 8);
        request[5 + address.length] = (byte) (port & 0xFF);
        out.write(request);
        out.flush();
        byte[] reply = in.readNBytes(4);
        if (reply.length < 4 || reply[1] != 0x00) {
            throw new IOException("SOCKS5 代理拒绝连接（code=" + (reply.length > 1 ? reply[1] : -1) + "）");
        }
        int skip;
        switch (reply[3]) {
            case 0x01 -> skip = 4;
            case 0x04 -> skip = 16;
            case 0x03 -> {
                int length = in.read();
                skip = length < 0 ? 0 : length;
            }
            default -> skip = 0;
        }
        if (skip > 0) {
            in.readNBytes(skip);
        }
        in.readNBytes(2); // 绑定端口
    }

    // ==================== 字节对接 ====================

    private static void pump(Socket client, Socket upstream) {
        Thread toUpstream = new Thread(() -> copy(client, upstream), "socks5-bridge-up");
        toUpstream.setDaemon(true);
        toUpstream.start();
        copy(upstream, client);
        try {
            upstream.close();
        } catch (IOException ignored) {
            // 忽略
        }
        try {
            client.close();
        } catch (IOException ignored) {
            // 忽略
        }
    }

    private static void copy(Socket from, Socket to) {
        try {
            from.getInputStream().transferTo(to.getOutputStream());
        } catch (IOException ignored) {
            // 任一端断开：结束这条隧道
        } finally {
            try {
                to.shutdownOutput();
            } catch (IOException ignored) {
                // 忽略
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder builder = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b < 0) {
                return builder.isEmpty() ? null : builder.toString();
            }
            if (b == '\n') {
                break;
            }
            builder.append((char) b);
        }
        int length = builder.length();
        if (length > 0 && builder.charAt(length - 1) == '\r') {
            builder.setLength(length - 1);
        }
        return builder.toString();
    }

    private static void write(Socket socket, String text) throws IOException {
        socket.getOutputStream().write(text.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }
}
