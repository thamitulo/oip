package za.co.oip.support;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A tiny local HTTP server serving canned responses, used to exercise crawler adapters end to end
 * without contacting real procurement websites. Routes can be changed between crawls.
 */
public class FixtureServer implements AutoCloseable {

    public record Response(int status, String contentType, String body) {
    }

    /** A response with a binary body (e.g. a PDF). */
    public record BinaryResponse(int status, String contentType, byte[] data) {
    }

    private final HttpServer server;
    private final Map<String, Response> routes = new ConcurrentHashMap<>();
    private final Map<String, BinaryResponse> binaryRoutes = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();

    public FixtureServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            hits.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
            BinaryResponse bin = binaryRoutes.get(path);
            Response r = bin != null ? new Response(bin.status(), bin.contentType(), "")
                    : routes.getOrDefault(path, new Response(404, "text/plain", "not found"));
            byte[] body = bin != null ? bin.data() : r.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", r.contentType());
            exchange.sendResponseHeaders(r.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
            exchange.close();
        });
        server.start();
    }

    public FixtureServer route(String path, int status, String contentType, String body) {
        routes.put(path, new Response(status, contentType, body));
        return this;
    }

    public FixtureServer html(String path, String body) {
        return route(path, 200, "text/html; charset=utf-8", body);
    }

    public Map<String, BinaryResponse> routes() {
        return binaryRoutes;
    }

    public String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    public int hits(String path) {
        AtomicInteger n = hits.get(path);
        return n == null ? 0 : n.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
