package app.m8.eclipse.jdtsearch;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal HTTP server on a raw ServerSocket.
 * Handles GET requests, parses path + query params, dispatches to handlers.
 */
public class HttpServer {

    private final int port;
    private final SearchHandler search = new SearchHandler();
    private final DiagnosticsHandler diagnostics = new DiagnosticsHandler();
    private final RefactoringHandler refactoring = new RefactoringHandler();
    private final EditorHandler editor = new EditorHandler();
    private volatile ServerSocket serverSocket;
    private volatile boolean running;

    /** Response with content type, optional headers, and body. */
    record Response(String contentType, Map<String, String> headers, String body) {
        static Response json(String json) {
            return new Response("application/json", Map.of(), json);
        }

        static Response text(String body, Map<String, String> headers) {
            return new Response("text/plain", headers, body);
        }
    }

    public HttpServer(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        Thread t = new Thread(this::acceptLoop, "jdt-search-http");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException e) { /* ignore */ }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                Thread handler = new Thread(() -> handle(socket), "jdt-search-req");
                handler.setDaemon(true);
                handler.start();
            } catch (IOException e) {
                if (running) {
                    System.err.println("[jdt-search] Accept error: " + e.getMessage());
                }
            }
        }
    }

    private void handle(Socket socket) {
        try (socket) {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String requestLine = reader.readLine();
            if (requestLine == null) return;

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;

            String fullPath = parts[1];
            String path;
            Map<String, String> params;
            int q = fullPath.indexOf('?');
            if (q >= 0) {
                path = fullPath.substring(0, q);
                params = parseQuery(fullPath.substring(q + 1));
            } else {
                path = fullPath;
                params = Map.of();
            }

            Response resp = dispatch(path, params);

            StringBuilder sb = new StringBuilder();
            sb.append("HTTP/1.1 200 OK\r\n");
            sb.append("Content-Type: ").append(resp.contentType)
                    .append("; charset=utf-8\r\n");
            sb.append("Connection: close\r\n");
            for (var entry : resp.headers.entrySet()) {
                sb.append(entry.getKey()).append(": ")
                        .append(entry.getValue()).append("\r\n");
            }
            sb.append("\r\n");

            OutputStream out = socket.getOutputStream();
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.write(resp.body.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception e) {
            System.err.println("[jdt-search] Request error: " + e.getMessage());
        }
    }

    private Response dispatch(String path, Map<String, String> params) {
        try {
            return switch (path) {
                case "/projects" -> Response.json(search.handleProjects());
                case "/find" -> Response.json(search.handleFind(params));
                case "/references" -> Response.json(search.handleReferences(params));
                case "/subtypes" -> Response.json(search.handleSubtypes(params));
                case "/hierarchy" -> Response.json(search.handleHierarchy(params));
                case "/implementors" -> Response.json(
                        search.handleImplementors(params));
                case "/errors" -> Response.json(diagnostics.handleErrors(params));
                case "/type-info" -> Response.json(search.handleTypeInfo(params));
                case "/source" -> search.handleSource(params);
                case "/organize-imports" -> Response.json(
                        refactoring.handleOrganizeImports(params));
                case "/format" -> Response.json(refactoring.handleFormat(params));
                case "/rename" -> Response.json(refactoring.handleRename(params));
                case "/move" -> Response.json(refactoring.handleMove(params));
                case "/active-editor" -> Response.json(
                        editor.handleActiveEditor(params));
                case "/open" -> Response.json(editor.handleOpen(params));
                default -> Response.json(
                        "{\"error\":\"Unknown path: " + escapeJson(path) + "\"}");
            };
        } catch (Exception e) {
            return Response.json(
                    "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = URLDecoder.decode(
                        pair.substring(0, eq), StandardCharsets.UTF_8);
                String val = URLDecoder.decode(
                        pair.substring(eq + 1), StandardCharsets.UTF_8);
                params.put(key, val);
            } else if (!pair.isBlank()) {
                params.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
            }
        }
        return params;
    }

    /** JSON string escaping — handles all control characters per RFC 8259. */
    static String escapeJson(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
