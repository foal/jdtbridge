package io.github.kaluchi.jdtbridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Serves the welcome/status HTML page and handles the dismiss action.
 * No auth required — loopback only, read-only page + boolean config.
 *
 * Reads port/version from the bridge instance files in ~/.jdtbridge/
 * rather than relying on static state.
 */
class WelcomeHandler {

    private static final String CONFIG_FILE = "config.json";
    private static final String DISMISSED_KEY = "welcomeDismissed";

    HttpServer.Response handleStatus() throws IOException {
        String template = loadResource("welcome.html");
        BridgeInfo info = readBridgeInfo();
        CliInfo cli = detectCli();
        String html = template
                .replace("{{version}}", info.version)
                .replace("{{port}}", String.valueOf(info.port))
                .replace("{{cliInstalled}}",
                        String.valueOf(cli.installed))
                .replace("{{cliVersion}}", cli.version);
        return HttpServer.Response.html(html);
    }

    HttpServer.Response handleDismiss() {
        try {
            Path configFile = Activator.getHome().resolve(CONFIG_FILE);
            Map<String, Object> config;
            if (Files.exists(configFile)) {
                config = Json.parse(Files.readString(configFile,
                        StandardCharsets.UTF_8));
            } else {
                Files.createDirectories(configFile.getParent());
                config = new java.util.LinkedHashMap<>();
            }
            config.put(DISMISSED_KEY, Boolean.TRUE);
            // Rebuild JSON from map
            Json builder = Json.object();
            for (var entry : config.entrySet()) {
                Object v = entry.getValue();
                if (v instanceof String s) builder.put(entry.getKey(), s);
                else if (v instanceof Boolean b) builder.put(entry.getKey(), b);
                else if (v instanceof Integer i) builder.put(entry.getKey(), i);
                else if (v instanceof Long l) builder.put(entry.getKey(), l);
                else if (v instanceof Double d) builder.put(entry.getKey(), d);
                else if (v == null) builder.put(entry.getKey(), (String) null);
            }
            Files.writeString(configFile, builder.toString() + "\n",
                    StandardCharsets.UTF_8);
            return HttpServer.Response.json("{\"ok\":true}");
        } catch (IOException e) {
            Log.warn("Failed to save dismiss preference", e);
            return HttpServer.Response.json(
                    Json.error("Failed to save: " + e.getMessage()));
        }
    }

    static boolean isDismissed() {
        try {
            Path configFile = Activator.getHome().resolve(CONFIG_FILE);
            if (!Files.exists(configFile)) return false;
            var config = Json.parse(Files.readString(configFile,
                    StandardCharsets.UTF_8));
            return Json.getBool(config, DISMISSED_KEY, false);
        } catch (IOException e) {
            return false;
        }
    }

    static boolean isCliInstalled() {
        return detectCli().installed;
    }

    private static CliInfo detectCli() {
        try {
            boolean win = System.getProperty("os.name")
                    .toLowerCase().contains("win");
            String[] cmd = win
                    ? new String[]{"cmd", "/c", "npm", "list", "-g",
                            "@kaluchi/jdtbridge", "--json"}
                    : new String[]{"npm", "list", "-g",
                            "@kaluchi/jdtbridge", "--json"};
            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(
                    p.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            boolean exited = p.waitFor(10,
                    java.util.concurrent.TimeUnit.SECONDS);
            if (!exited) {
                p.destroyForcibly();
                return new CliInfo(false, "");
            }
            var outer = Json.parse(output);
            String depsRaw = Json.getString(outer, "dependencies");
            if (depsRaw == null) return new CliInfo(false, "");
            var deps = Json.parse(depsRaw);
            String pkgRaw = Json.getString(deps,
                    "@kaluchi/jdtbridge");
            if (pkgRaw == null) return new CliInfo(false, "");
            var pkg = Json.parse(pkgRaw);
            String version = Json.getString(pkg, "version");
            if (version != null && !version.isEmpty()) {
                return new CliInfo(true, version);
            }
            return new CliInfo(false, "");
        } catch (Exception e) {
            return new CliInfo(false, "");
        }
    }

    private record CliInfo(boolean installed, String version) {
    }

    /** Read port and version from the first bridge instance file. */
    private BridgeInfo readBridgeInfo() {
        try {
            Path instances = Activator.getHome().resolve("instances");
            if (!Files.isDirectory(instances)) {
                return new BridgeInfo(0, "");
            }
            try (var files = Files.list(instances)) {
                return files
                        .filter(f -> f.toString().endsWith(".json"))
                        .findFirst()
                        .map(this::parseBridgeFile)
                        .orElse(new BridgeInfo(0, ""));
            }
        } catch (IOException e) {
            return new BridgeInfo(0, "");
        }
    }

    private BridgeInfo parseBridgeFile(Path file) {
        try {
            var map = Json.parse(Files.readString(file,
                    StandardCharsets.UTF_8));
            int port = Json.getInt(map, "port", 0);
            String version = Json.getString(map, "version");
            return new BridgeInfo(port, version != null ? version : "");
        } catch (IOException e) {
            return new BridgeInfo(0, "");
        }
    }

    private record BridgeInfo(int port, String version) {
    }

    private String loadResource(String name) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(name)) {
            if (is == null) {
                throw new IOException("Resource not found: " + name);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
