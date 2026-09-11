package com.coverageloop.desktop;

import com.coverageloop.util.Json;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Private loopback service. The renderer never receives the port or bearer token. */
public final class DesktopServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService http = Executors.newFixedThreadPool(4);
    private final DesktopEngine engine;
    private final String token;

    public DesktopServer(String token) throws IOException { this(token, new DesktopEngine()); }
    DesktopServer(String token, DesktopEngine engine) throws IOException {
        this.engine = engine;
        if (token == null || token.length() < 32) throw new IllegalArgumentException("COVERAGE_SESSION_TOKEN is required (32+ characters)");
        this.token = token;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        server.setExecutor(http);
        server.createContext("/", this::handle);
    }
    public int port() { return server.getAddress().getPort(); }
    public void start() { server.start(); }
    private void handle(HttpExchange exchange) throws IOException {
        try {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (exchange.getRequestHeaders().containsKey("Origin") || auth == null || !MessageDigest.isEqual(
                    ("Bearer " + token).getBytes(StandardCharsets.UTF_8), auth.getBytes(StandardCharsets.UTF_8))) {
                reply(exchange, 401, Map.of("error", "Unauthorized")); return;
            }
            String path = exchange.getRequestURI().getPath();
            if ("/health".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                reply(exchange, 200, Map.of("version", "1.3.0", "java", System.getProperty("java.version"))); return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) { reply(exchange, 405, Map.of("error", "POST required")); return; }
            byte[] bytes = exchange.getRequestBody().readNBytes(1_048_577);
            if (bytes.length > 1_048_576) { reply(exchange, 413, Map.of("error", "Request too large")); return; }
            JsonObject input = bytes.length == 0 ? new JsonObject() : JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            reply(exchange, 200, engine.request(path, input));
        } catch (IllegalStateException e) {
            reply(exchange, 409, Map.of("error", safeMessage(e)));
        } catch (IllegalArgumentException e) {
            reply(exchange, 400, Map.of("error", safeMessage(e)));
        } catch (Exception e) {
            reply(exchange, 500, Map.of("error", safeMessage(e)));
        } finally { exchange.close(); }
    }
    private static String safeMessage(Exception e) { return e.getMessage() == null ? "操作失败，请检查配置" : e.getMessage(); }
    private static void reply(HttpExchange x, int code, Object body) throws IOException {
        byte[] bytes = Json.toCompactJson(body).getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        x.getResponseHeaders().set("Cache-Control", "no-store");
        x.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        x.sendResponseHeaders(code, bytes.length);
        x.getResponseBody().write(bytes);
    }
    @Override public void close() { engine.close(); server.stop(0); http.shutdownNow(); }
    public static void main(String[] args) throws Exception {
        String legacy = System.getenv("COVERAGE_IMPORT_DB");
        if (legacy != null && !legacy.isBlank()) WorkspaceImport.importIfNeeded(java.nio.file.Path.of(legacy), WorkspaceStore.defaultPath());
        DesktopServer service = new DesktopServer(System.getenv("COVERAGE_SESSION_TOKEN"));
        Runtime.getRuntime().addShutdownHook(new Thread(service::close));
        service.start();
        System.out.println("COVERAGE_READY " + service.port());
        // The parent owns stdin; EOF also cleans up jobs after an unexpected desktop exit.
        if ("1".equals(System.getenv("COVERAGE_PARENT_PIPE"))) {
            Thread parent = new Thread(() -> { try { while (System.in.read() != -1) {} } catch (IOException ignored) {} service.close(); }, "desktop-parent");
            parent.setDaemon(true); parent.start();
        }
    }
}
