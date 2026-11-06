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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bridges browser WebSocket connections to the existing line-based ChatServer TCP protocol.
 * Run ChatServer first. This gateway does not replace or modify the chat protocol.
 */
public class WebGateway extends WebSocketServer {
    private final String tcpHost;
    private final int tcpPort;
    private final Map<String, WebSocket> authenticatedUsers = new ConcurrentHashMap<>();
    private final Map<WebSocket, String> usernames = new ConcurrentHashMap<>();
    private final Map<WebSocket, WebSocket> callPeers = new ConcurrentHashMap<>();
    private final Map<WebSocket, String> callModes = new ConcurrentHashMap<>();

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
        if (command.equals("RTC")) {
            handleRtc(ws, message);
            return;
        }
        // Existing chat commands continue to be forwarded unchanged.
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
        removeUser(ws);
        Bridge bridge = ws.getAttachment();
        if (bridge != null) bridge.close();
    }

    private void handleRtc(WebSocket ws, String message) {
        String[] parts = message.split("\\|", 4);
        if (parts.length < 3) { rtcError(ws, "Malformed call command."); return; }
        String action = parts[1].toUpperCase(Locale.ROOT);
        String currentUser = usernames.get(ws);
        if (currentUser == null) { rtcError(ws, "Log in before calling."); return; }
        try {
            switch (action) {
                case "CALL": {
                    if (parts.length < 4) { rtcError(ws, "Choose audio or video."); return; }
                    String targetName = parts[2];
                    String mode = parts[3].toLowerCase(Locale.ROOT);
                    if (!mode.equals("audio") && !mode.equals("video")) { rtcError(ws, "Invalid call type."); return; }
                    WebSocket target = authenticatedUsers.get(targetName.toLowerCase(Locale.ROOT));
                    if (target == null || !target.isOpen()) { rtcError(ws, "That user is not online."); return; }
                    if (target == ws) { rtcError(ws, "You cannot call yourself."); return; }
                    if (callPeers.containsKey(ws) || callPeers.containsKey(target)) { rtcError(ws, "One of the users is already in a call."); return; }
                    callPeers.put(ws, target); callPeers.put(target, ws);
                    callModes.put(ws, mode); callModes.put(target, mode);
                    target.send("RTC|INCOMING|" + currentUser + "|" + mode);
                    ws.send("RTC|RINGING|" + targetName + "|" + mode);
                    break;
                }
                case "ACCEPT": {
                    String callerName = parts[2];
                    WebSocket caller = authenticatedUsers.get(callerName.toLowerCase(Locale.ROOT));
                    if (caller == null || callPeers.get(ws) != caller || callPeers.get(caller) != ws) { rtcError(ws, "Call is no longer available."); return; }
                    caller.send("RTC|ACCEPTED|" + currentUser);
                    ws.send("RTC|ACCEPTED_BY_YOU|" + callerName);
                    break;
                }
                case "REJECT": {
                    String callerName = parts[2];
                    WebSocket caller = authenticatedUsers.get(callerName.toLowerCase(Locale.ROOT));
                    if (caller != null && callPeers.get(ws) == caller) caller.send("RTC|REJECTED|" + currentUser);
                    clearCall(ws, false);
                    break;
                }
                case "OFFER": case "ANSWER": case "ICE": {
                    if (parts.length < 4) { rtcError(ws, "Missing call data."); return; }
                    WebSocket peer = callPeers.get(ws);
                    if (peer == null || !peer.isOpen()) { rtcError(ws, "No active call."); clearCall(ws, false); return; }
                    peer.send("RTC|" + action + "|" + currentUser + "|" + parts[3]);
                    break;
                }
                case "END": {
                    WebSocket peer = callPeers.get(ws);
                    if (peer != null && peer.isOpen()) peer.send("RTC|ENDED|" + currentUser);
                    clearCall(ws, false);
                    break;
                }
                default: rtcError(ws, "Unsupported call command.");
            }
        } catch (RuntimeException ex) {
            rtcError(ws, "Could not process call command.");
        }
    }

    private void rtcError(WebSocket ws, String message) {
        if (ws != null && ws.isOpen()) ws.send("RTC|ERROR|" + message.replace('|', ' '));
    }

    private void clearCall(WebSocket ws, boolean notifyPeer) {
        WebSocket peer = callPeers.remove(ws);
        callModes.remove(ws);
        if (peer != null) {
            callPeers.remove(peer, ws);
            callModes.remove(peer);
            if (notifyPeer && peer.isOpen()) peer.send("RTC|ENDED|" + usernames.getOrDefault(ws, "User"));
        }
    }

    private void removeUser(WebSocket ws) {
        String name = usernames.remove(ws);
        if (name != null) authenticatedUsers.remove(name.toLowerCase(Locale.ROOT), ws);
        clearCall(ws, true);
    }

    private void registerAuthenticatedUser(WebSocket ws, String line) {
        if (!line.startsWith("AUTH_OK|")) return;
        String name = line.substring("AUTH_OK|".length()).trim();
        if (name.isEmpty()) return;
        String key = name.toLowerCase(Locale.ROOT);
        WebSocket old = authenticatedUsers.put(key, ws);
        if (old != null && old != ws) {
            usernames.remove(old);
            clearCall(old, true);
        }
        usernames.put(ws, name);
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
                        registerAuthenticatedUser(ws, line);
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
