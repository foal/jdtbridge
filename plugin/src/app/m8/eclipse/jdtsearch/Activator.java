package app.m8.eclipse.jdtsearch;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

public class Activator implements BundleActivator {

    static final int PORT = 7891;
    private HttpServer server;

    @Override
    public void start(BundleContext context) throws Exception {
        server = new HttpServer(PORT);
        server.start();
        System.out.println("[jdt-search] HTTP server started on port " + PORT);
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        if (server != null) {
            server.stop();
            server = null;
        }
        System.out.println("[jdt-search] HTTP server stopped");
    }
}
