import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bridges browser WebSocket connections to the existing line-based ChatServer TCP protocol.
 * Run ChatServer first. This gateway does not replace or modify the chat protocol.
 */
public class WebGateway extends WebSocketServer {
    private final String tcpHost;
    private final int tcpPort;

    public WebGateway(InetSocketAddress address, String tcpHost, int tcpPort) {
        super(address);
        this.tcpHost = tcpHost;
        this.tcpPort = tcpPort;
        setConnectionLostTimeout(30);
    }

    @Override
    public void onOpen(WebSocket ws, ClientHandshake handshake) {
        try {
            Bridge bridge = new Bridge(ws, tcpHost, tcpPort);
            ws.setAttachment(bridge);
            bridge.start();
        } catch (IOException e) {
            ws.send("ERROR|Could not connect to chat server. Start ChatServer first.");
            ws.close(1011, "Chat server unavailable");
        }
    }

    @Override
    public void onMessage(WebSocket ws, String message) {
        Bridge bridge = ws.getAttachment();
        if (bridge == null || bridge.closed.get()) {
            ws.close(1011, "Bridge unavailable");
            return;
        }
        if (message == null || message.length() > 3_000_000 ||
                message.indexOf('\n') >= 0 || message.indexOf('\r') >= 0 || message.indexOf('\0') >= 0) {
            ws.send("ERROR|Invalid or oversized message.");
            return;
        }

        String command = commandOf(message).toUpperCase(Locale.ROOT);
        // Browser clients may only issue commands understood by the original server.
        if (!command.equals("LOGIN") && !command.equals("REGISTER") &&
            !command.equals("MSG") && !command.equals("PM") &&
            !command.equals("FILE") && !command.equals("QUIT")) {
            ws.send("ERROR|Unsupported command.");
            return;
        }

        if (!bridge.send(message)) {
            ws.send("ERROR|Chat server connection was lost. Refresh to reconnect.");
            bridge.close();
        }
    }

    @Override
    public void onClose(WebSocket ws, int code, String reason, boolean remote) {
        Bridge bridge = ws.getAttachment();
        if (bridge != null) bridge.close();
    }

    @Override
    public void onError(WebSocket ws, Exception ex) {
        System.err.println("WebSocket error: " + (ex == null ? "unknown" : ex.getMessage()));
        if (ws != null) ws.close(1011, "Gateway error");
    }

    @Override
    public void onStart() {
        System.out.println("WebSocket gateway listening on ws://localhost:" + getPort());
        System.out.println("Open http://localhost:" + getPort() + "/");
        System.out.println("TCP chat server target: " + tcpHost + ":" + tcpPort);
    }

    private static String commandOf(String line) {
        int p = line.indexOf('|');
        return p < 0 ? line : line.substring(0, p);
    }

    private final class Bridge {
        private final WebSocket ws;
        private final Socket socket = new Socket();
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private PrintWriter out;

        Bridge(WebSocket ws, String host, int port) throws IOException {
            this.ws = ws;
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
        }

        void start() {
            Thread reader = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while (!closed.get() && (line = readProtocolLine(in)) != null) {
                        if (ws.isOpen()) ws.send(line);
                        else break;
                    }
                } catch (IOException e) {
                    if (ws.isOpen()) ws.send("ERROR|Connection to chat server was lost.");
                } finally {
                    close();
                    if (ws.isOpen()) ws.close(1000, "Chat server connection closed");
                }
            }, "web-gateway-tcp-reader");
            reader.setDaemon(true);
            reader.start();
        }

        synchronized boolean send(String line) {
            if (closed.get() || out == null) return false;
            out.println(line);
            return !out.checkError();
        }

        void close() {
            if (!closed.compareAndSet(false, true)) return;
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    // Mirrors Protocol.readLine's bounded line handling without depending on the default
    // BufferedReader.readLine() implementation for a very large base64 file message.
    private static String readProtocolLine(BufferedReader in) throws IOException {
        StringBuilder b = new StringBuilder();
        int ch;
        while ((ch = in.read()) != -1) {
            if (ch == '\n') return b.toString();
            if (ch == '\r') return b.toString();
            if (b.length() > 3_000_000) throw new IOException("Line too long");
            b.append((char) ch);
        }
        return b.length() == 0 ? null : b.toString();
    }

    public static void main(String[] args) throws Exception {
        int wsPort = args.length > 0 ? Integer.parseInt(args[0]) : 8081;
        String tcpHost = args.length > 1 ? args[1] : "127.0.0.1";
        int tcpPort = args.length > 2 ? Integer.parseInt(args[2]) : 12345;
        WebGateway gateway = new WebGateway(new InetSocketAddress("0.0.0.0", wsPort), tcpHost, tcpPort);
        gateway.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { gateway.stop(1000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }));
    }
}
