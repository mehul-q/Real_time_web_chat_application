import java.io.BufferedReader;

import java.io.IOException;

import java.io.PrintWriter;

import java.net.BindException;

import java.net.Inet4Address;

import java.net.InetAddress;

import java.net.NetworkInterface;

import java.net.ServerSocket;

import java.net.Socket;

import java.net.SocketException;

import java.util.ArrayList;

import java.util.Base64;

import java.util.Collections;

import java.util.List;

import java.util.Locale;

import java.util.Map;

import java.util.concurrent.ConcurrentHashMap;








public class ChatServer {

    private static final int LOGIN_TIMEOUT_MS = 60_000;

    private static final int MAX_LOGIN_ATTEMPTS = 5;

    private static final int HISTORY_SIZE = 50;




    private static final Map<String, ClientHandler> online = new ConcurrentHashMap<>();

    private static final UserStore users = new UserStore("users.txt");

    private static final ChatHistory history = new ChatHistory("chat_history.txt", HISTORY_SIZE);



    public static void main(String[] args) throws IOException {

        int port = args.length > 0 ? Integer.parseInt(args[0]) : Protocol.DEFAULT_PORT;



        try (ServerSocket serverSocket = new ServerSocket(port)) {

            System.out.println("Chat server started on port " + port);

            printAddresses();

            System.out.println("Accounts are saved in users.txt, public chat history in chat_history.txt");

            System.out.println("Waiting for clients...");



            while (true) {

                Socket socket = serverSocket.accept();

                new Thread(new ClientHandler(socket)).start();

            }

        } catch (BindException e) {

            System.out.println("Port " + port + " is already in use. Is another chat server already running?");

        }

    }




    private static void printAddresses() {

        try {

            System.out.println("Other devices on the same network can connect to:");

            boolean found = false;

            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {

                if (!nic.isUp() || nic.isLoopback()) {

                    continue;

                }

                for (InetAddress address : Collections.list(nic.getInetAddresses())) {

                    if (address instanceof Inet4Address) {

                        System.out.println("   " + address.getHostAddress() + "   (" + nic.getDisplayName() + ")");

                        found = true;

                    }

                }

            }

            if (!found) {

                System.out.println("   (no network address found - only this computer can connect)");

            }

        } catch (SocketException ignored) {

        }

    }




    static void broadcast(String line, ClientHandler exclude) {

        for (ClientHandler client : online.values()) {

            if (client.ready && client != exclude) {

                client.send(line);

            }

        }

    }




    static synchronized void broadcastUserList() {

        List<String> names = new ArrayList<>();

        for (ClientHandler client : online.values()) {

            if (client.ready) {

                names.add(client.username);

            }

        }

        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);

        broadcast("USERS|" + String.join(",", names), null);

    }



    private static String now() {

        return String.valueOf(System.currentTimeMillis());

    }




    static class ClientHandler implements Runnable {

        private final Socket socket;

        private BufferedReader in;

        private PrintWriter out;

        private volatile String username;     // set once login succeeds*

        private volatile boolean ready;       // true once the client is fully logged in and may receive broadcasts*

        private volatile boolean replaced;    // true if a newer login of the same account took this session over*

        private boolean tookOver;             // true if this login replaced an older session*



        ClientHandler(Socket socket) {

            this.socket = socket;

        }



        void send(String line) {

            out.println(line);

        }



        @Override

        public void run() {

            try {

                socket.setTcpNoDelay(true);

                socket.setKeepAlive(true);

                in = Protocol.newReader(socket);

                out = Protocol.newWriter(socket);



                socket.setSoTimeout(LOGIN_TIMEOUT_MS);      // don't let silent connections hang around forever*

                if (!authenticate()) {

                    return;

                }

                socket.setSoTimeout(0);



                send("AUTH_OK|" + username);

                sendHistory();

                ready = true;

                System.out.println(username + " logged in from " + socket.getRemoteSocketAddress());

                if (!tookOver) {

                    broadcast("JOIN|" + now() + "|" + username, this);

                }

                broadcastUserList();



                commandLoop();

            } catch (IOException e) {

                if (username != null && !replaced) {

                    System.out.println("Connection lost with " + username + ": " + e.getMessage());

                }

            } finally {

                disconnect();

            }

        }




        private boolean authenticate() throws IOException {

            int failures = 0;

            String line;

            while ((line = Protocol.readLine(in)) != null) {

                String command = Protocol.commandOf(line);

                if (command.equals("QUIT")) {

                    return false;

                }

                String[] f = Protocol.fields(Protocol.argsOf(line), 2);

                String error;

                String account = null;



                if ((!command.equals("LOGIN") && !command.equals("REGISTER")) || f.length < 2) {

                    error = "Please log in or register first.";

                } else if (command.equals("REGISTER")) {

                    error = users.register(f[0], f[1]);

                    if (error == null) {

                        System.out.println("New account created: " + f[0]);

                        account = f[0];

                    }

                } else {

                    account = users.authenticate(f[0], f[1]);

                    error = (account == null) ? "Invalid username or password." : null;

                }



                if (error == null) {

                    claim(account);

                    return true;

                }

                if (++failures >= MAX_LOGIN_ATTEMPTS) {

                    send("AUTH_FAIL|Too many failed attempts. Disconnecting.");

                    return false;

                }

                send("AUTH_FAIL|" + error);

            }

            return false;

        }







        private void claim(String account) {

            username = account;

            ClientHandler previous = online.put(account.toLowerCase(Locale.ROOT), this);

            if (previous != null) {

                tookOver = true;

                previous.replaced = true;

                previous.send("ERROR|You were logged out because this account logged in from another place.");

                previous.closeSocket();

                System.out.println(account + " logged in again - the old session was closed");

            }

        }



        private void closeSocket() {

            try {

                socket.close();

            } catch (IOException ignored) {

            }

        }



        private void sendHistory() {

            List<String> recent = history.recent();

            if (recent.isEmpty()) {

                return;

            }

            send("HIST_BEGIN");

            for (String entry : recent) {

                send("HIST|" + entry);

            }

            send("HIST_END");

        }



        private void commandLoop() throws IOException {

            String line;

            while ((line = Protocol.readLine(in)) != null) {

                if (line.isEmpty()) {

                    continue;

                }

                String command = Protocol.commandOf(line);

                String args = Protocol.argsOf(line);

                switch (command) {

                    case "MSG":

                        handleMessage(args);

                        break;

                    case "PM":

                        handlePrivateMessage(args);

                        break;

                    case "FILE":

                        handleFile(args);

                        break;

                    case "QUIT":

                        return;

                    default:

                        send("ERROR|Unknown command.");

                }

            }

        }



        private void handleMessage(String text) {

            text = text.trim();

            if (text.isEmpty()) {

                return;

            }

            if (text.length() > Protocol.MAX_MESSAGE_LENGTH) {

                send("ERROR|Message too long (the limit is " + Protocol.MAX_MESSAGE_LENGTH + " characters).");

                return;

            }

            long time = System.currentTimeMillis();

            history.add(time, username, text);

            System.out.println(username + ": " + text);

            broadcast("MSG|" + time + "|" + username + "|" + text, null);

        }



        private void handlePrivateMessage(String args) {

            String[] f = Protocol.fields(args, 2);

            if (f.length < 2 || f[0].trim().isEmpty() || f[1].trim().isEmpty()) {

                send("ERROR|Usage: /msg username message");

                return;

            }

            String targetName = f[0].trim();

            String text = f[1].trim();

            if (text.length() > Protocol.MAX_MESSAGE_LENGTH) {

                send("ERROR|Message too long (the limit is " + Protocol.MAX_MESSAGE_LENGTH + " characters).");

                return;

            }

            ClientHandler target = online.get(targetName.toLowerCase(Locale.ROOT));

            if (target == null || !target.ready) {

                send("ERROR|" + targetName + " is not online.");

                return;

            }

            if (target == this) {

                send("ERROR|You can't send a private message to yourself.");

                return;

            }

            String time = now();

            target.send("PM|" + time + "|" + username + "|" + text);

            send("PMSENT|" + time + "|" + target.username + "|" + text);

            System.out.println("[private] " + username + " -> " + target.username);   // content is not logged*

        }



        private void handleFile(String args) {

            String[] f = Protocol.fields(args, 2);

            if (f.length < 2) {

                send("ERROR|Invalid file.");

                return;

            }

            String name = Protocol.sanitizeFileName(f[0]);

            byte[] data;

            try {

                data = Base64.getDecoder().decode(f[1]);

            } catch (IllegalArgumentException e) {

                send("ERROR|Invalid file data.");

                return;

            }

            if (data.length == 0 || data.length > Protocol.MAX_FILE_BYTES) {

                send("ERROR|Files must be between 1 byte and " + Protocol.formatSize(Protocol.MAX_FILE_BYTES) + ".");

                return;

            }

            System.out.println(username + " shared a file: " + name + " (" + Protocol.formatSize(data.length) + ")");

            broadcast("FILE|" + now() + "|" + username + "|" + name + "|" + f[1], null);

        }



        private void disconnect() {

            if (username != null) {

                online.remove(username.toLowerCase(Locale.ROOT), this);    // only removes us if we are still the current session*

                if (ready) {

                    ready = false;

                    if (!replaced) {                       // a replaced session isn't a real departure: the user is still online*

                        System.out.println(username + " disconnected");

                        broadcast("LEAVE|" + now() + "|" + username, null);

                        broadcastUserList();

                    }

                }

            }

            closeSocket();

        }

    }

}