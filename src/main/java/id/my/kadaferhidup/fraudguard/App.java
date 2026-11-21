package id.my.kadaferhidup.fraudguard;

import com.sun.net.httpserver.HttpServer;
import id.my.kadaferhidup.fraudguard.http.Api;
import id.my.kadaferhidup.fraudguard.http.RateLimiter;
import id.my.kadaferhidup.fraudguard.risk.RiskEngine;
import id.my.kadaferhidup.fraudguard.risk.Thresholds;
import id.my.kadaferhidup.fraudguard.workspace.Workspaces;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/** Wires fraud-guard together and starts the HTTP server. */
public final class App implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(App.class.getName());

    public record Config(int port, int requestsPerMinute, int sandboxesPerHour) {

        public static Config fromEnv() {
            return new Config(intEnv("PORT", 8080), intEnv("RATE_LIMIT_PER_MINUTE", 300), intEnv("SANDBOX_PER_HOUR", 10));
        }

        private static int intEnv(String name, int fallback) {
            String v = System.getenv(name);
            return v == null || v.isBlank() ? fallback : Integer.parseInt(v.strip());
        }
    }

    private final HttpServer server;
    private final Workspaces workspaces;

    private App(HttpServer server, Workspaces workspaces) {
        this.server = server;
        this.workspaces = workspaces;
    }

    public static App start(Config cfg, Clock clock) throws IOException {
        Workspaces ws = new Workspaces(clock, new RiskEngine(Thresholds.defaults()));
        Api api = new Api(ws, new RateLimiter(clock, cfg.requestsPerMinute(), Math.max(20, cfg.requestsPerMinute() / 4)),
                new RateLimiter(clock, cfg.sandboxesPerHour(), Duration.ofHours(1), Math.min(3, cfg.sandboxesPerHour())), clock);
        HttpServer server = HttpServer.create(new InetSocketAddress(cfg.port()), 128);
        server.createContext("/", api);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return new App(server, ws);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public Workspaces workspaces() {
        return workspaces;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    public static void main(String[] args) throws IOException {
        App app = start(Config.fromEnv(), Clock.systemUTC());
        LOG.info("fraud-guard jalan di port " + app.port());
    }
}
