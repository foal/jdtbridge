package app.m8.eclipse.jdtsearch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;

import org.eclipse.core.resources.ResourcesPlugin;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

public class Activator implements BundleActivator {

    private static final String BRIDGE_FILE = ".jdt-bridge";
    private static final int TOKEN_BYTES = 16;

    private HttpServer server;
    private Path bridgeFile;

    @Override
    public void start(BundleContext context) throws Exception {
        String token = generateToken();

        server = new HttpServer();
        server.setToken(token);
        server.start();

        int port = server.getPort();
        writeBridgeFile(port, token);

        System.out.println("[jdt-search] HTTP server started on port "
                + port);
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        if (server != null) {
            server.stop();
            server = null;
        }
        deleteBridgeFile();
        System.out.println("[jdt-search] HTTP server stopped");
    }

    private void writeBridgeFile(int port, String token)
            throws IOException {
        bridgeFile = Path.of(System.getProperty("user.home"),
                BRIDGE_FILE);
        String workspace = ResourcesPlugin.getWorkspace().getRoot()
                .getLocation().toOSString();
        long pid = ProcessHandle.current().pid();

        String content = "port=" + port + "\n"
                + "token=" + token + "\n"
                + "pid=" + pid + "\n"
                + "workspace=" + workspace + "\n";
        Files.writeString(bridgeFile, content);
    }

    private void deleteBridgeFile() {
        if (bridgeFile != null) {
            try {
                Files.deleteIfExists(bridgeFile);
            } catch (IOException e) { /* ignore */ }
        }
    }

    private static String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(TOKEN_BYTES * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
