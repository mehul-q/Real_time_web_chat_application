import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/** Serves the static browser client on port 8080. WebSocket gateway defaults to port 8081. */
public final class WebFiles {
    private WebFiles() {}

    public static void main(String[] args) throws Exception {
        int port = args.length > 0
        ? Integer.parseInt(args[0])
        : Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", WebFiles::serve);
        server.start();
        System.out.println("Browser UI available at http://localhost:" + port);
    }

    private static void serve(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        String resource = "/web" + path;
        String type = path.endsWith(".css") ? "text/css; charset=utf-8"
                : path.endsWith(".js") ? "application/javascript; charset=utf-8"
                : "text/html; charset=utf-8";
        try (InputStream in = WebFiles.class.getResourceAsStream(resource)) {
            if (in == null || path.contains("..")) {
                byte[] notFound = "Not found".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(404, notFound.length);
                try (OutputStream out = exchange.getResponseBody()) { out.write(notFound); }
                return;
            }
            byte[] data = in.readAllBytes();
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", type);
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("X-Frame-Options", "DENY");
            headers.set("Referrer-Policy", "no-referrer");
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(data); }
        } finally {
            exchange.close();
        }
    }
}
