package cc.thonly.wikikernel.plugin.aiagent.service;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Socks5Bridge}：JDK HttpClient 不支持 SOCKS 代理，本类验证桥能正确完成
 * CONNECT → SOCKS5 握手 → 双向对接；并覆盖 socks5h（域名交给代理解析）/ socks5（本机解析）与拒绝分支。
 */
class Socks5BridgeTest {

    private static ServerSocket socksServer;
    private static volatile boolean acceptHandshake = true;
    private static volatile String lastTarget;
    private static volatile int lastPort;

    @BeforeAll
    static void startSocksServer() throws IOException {
        socksServer = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(() -> {
            while (!socksServer.isClosed()) {
                try {
                    Socket socket = socksServer.accept();
                    Thread worker = new Thread(() -> serveSocks(socket));
                    worker.setDaemon(true);
                    worker.start();
                } catch (IOException exception) {
                    return;
                }
            }
        }, "test-socks5");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @AfterAll
    static void stopSocksServer() throws IOException {
        if (socksServer != null) {
            socksServer.close();
        }
    }

    /** 极简 SOCKS5 服务端：无认证 → 记录目标 → 回复成功 → 之后原样回吐（当“远端”用）。 */
    private static void serveSocks(Socket socket) {
        try (socket) {
            InputStream in = socket.getInputStream();
            byte[] greeting = in.readNBytes(3);
            if (greeting.length < 3 || greeting[0] != 0x05) {
                return;
            }
            if (!acceptHandshake) {
                socket.getOutputStream().write(new byte[]{0x05, (byte) 0xFF});
                socket.getOutputStream().flush();
                return;
            }
            socket.getOutputStream().write(new byte[]{0x05, 0x00});
            socket.getOutputStream().flush();
            byte[] head = in.readNBytes(4);
            String target;
            if (head[3] == 0x03) {
                int length = in.read();
                target = new String(in.readNBytes(length), StandardCharsets.US_ASCII);
            } else if (head[3] == 0x01) {
                target = InetAddress.getByAddress(in.readNBytes(4)).getHostAddress();
            } else {
                target = InetAddress.getByAddress(in.readNBytes(16)).getHostAddress();
            }
            int port = (in.read() << 8) | in.read();
            lastTarget = target;
            lastPort = port;
            socket.getOutputStream().write(new byte[]{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
            socket.getOutputStream().flush();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                socket.getOutputStream().write(buffer, 0, read);
                socket.getOutputStream().flush();
            }
        } catch (IOException ignored) {
            // 连接结束
        }
    }

    @Test
    void tunnelsConnectThroughSocks5WithRemoteDns() throws Exception {
        acceptHandshake = true;
        Socks5Bridge bridge = Socks5Bridge.forProxy("127.0.0.1", socksServer.getLocalPort(), true);
        try (Socket client = connect(bridge, "api.example.com", 443)) {
            assertTrue(readLine(client.getInputStream()).contains("200"), "应回 200");
            while (!readLine(client.getInputStream()).isEmpty()) {
                // 跳过响应头
            }
            client.getOutputStream().write("ping".getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            assertEquals("ping", new String(client.getInputStream().readNBytes(4), StandardCharsets.US_ASCII));
            assertEquals("api.example.com", lastTarget);
            assertEquals(443, lastPort);
        }
    }

    @Test
    void resolvesLocallyForPlainSocks5() throws Exception {
        acceptHandshake = true;
        Socks5Bridge bridge = Socks5Bridge.forProxy("127.0.0.1", socksServer.getLocalPort(), false);
        try (Socket client = connect(bridge, "127.0.0.1", 8443)) {
            assertTrue(readLine(client.getInputStream()).contains("200"), "应回 200");
            while (!readLine(client.getInputStream()).isEmpty()) {
                // 跳过响应头
            }
            assertEquals("127.0.0.1", lastTarget, "socks5（不带 h）应本机解析后发 IP");
            assertEquals(8443, lastPort);
        }
    }

    @Test
    void returnsBadGatewayWhenSocksRejects() throws Exception {
        acceptHandshake = false;
        Socks5Bridge bridge = Socks5Bridge.forProxy("127.0.0.1", socksServer.getLocalPort(), false);
        try (Socket client = connect(bridge, "127.0.0.1", 8443)) {
            String status = readLine(client.getInputStream());
            assertTrue(status.contains("502"), "SOCKS 拒绝时应回 502，实际：" + status);
        } finally {
            acceptHandshake = true;
        }
    }

    private static Socket connect(Socks5Bridge bridge, String host, int port) throws IOException {
        Socket client = new Socket(InetAddress.getLoopbackAddress(), bridge.port());
        client.getOutputStream().write(("CONNECT " + host + ":" + port + " HTTP/1.1\r\nHost: " + host + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        client.getOutputStream().flush();
        return client;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder builder = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b < 0) {
                return builder.toString();
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
}