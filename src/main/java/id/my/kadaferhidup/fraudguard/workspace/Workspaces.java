package id.my.kadaferhidup.fraudguard.workspace;

import id.my.kadaferhidup.fraudguard.Problem;
import id.my.kadaferhidup.fraudguard.cases.Actor;
import id.my.kadaferhidup.fraudguard.risk.RiskEngine;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates sandboxes and maps credentials to them. Three secrets per sandbox, each with one power:
 * the API key may only submit transactions; the analyst token may investigate and propose; the supervisor
 * token may approve. Only SHA-256 hashes of the secrets are kept.
 */
public final class Workspaces {

    public record Grant(Workspace workspace, Actor actor, boolean ingest) {
    }

    public record Credentials(Workspace workspace, String apiKey, String analystToken, String supervisorToken) {
    }

    static final Duration LIFETIME = Duration.ofHours(24);
    static final int MAX_WORKSPACES = 2_000;

    private final Map<String, Workspace> byId = new ConcurrentHashMap<>();
    private final Map<String, Grant> grants = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final RiskEngine engine;

    public Workspaces(Clock clock, RiskEngine engine) {
        this.clock = clock;
        this.engine = engine;
    }

    public synchronized Credentials create() {
        evict();
        if (byId.size() >= MAX_WORKSPACES) {
            throw new Problem(429, "QUOTA", "Terlalu banyak sandbox aktif, coba lagi nanti");
        }
        String id;
        do {
            id = "ws-" + secret(6);
        } while (byId.containsKey(id));
        Workspace w = new Workspace(id, clock, engine);
        byId.put(id, w);
        String api = "fg_live_" + secret(24);
        String analyst = "fg_an_" + secret(24);
        String supervisor = "fg_sv_" + secret(24);
        grants.put(hash(api), new Grant(w, null, true));
        grants.put(hash(analyst), new Grant(w, w.analyst, false));
        grants.put(hash(supervisor), new Grant(w, w.supervisor, false));
        return new Credentials(w, api, analyst, supervisor);
    }

    /** The grant for a bearer secret, or 401. Expired sandboxes are gone. */
    public Grant authenticate(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7) || authorization.length() > 200) {
            throw new Problem(401, "UNAUTHORIZED", "Header Authorization: Bearer <token> wajib diisi");
        }
        Grant g = grants.get(hash(authorization.substring(7).strip()));
        if (g == null || !byId.containsKey(g.workspace().id())) {
            throw new Problem(401, "UNAUTHORIZED", "Token tidak dikenal atau sandbox sudah kedaluwarsa");
        }
        return g;
    }

    private void evict() {
        Instant cutoff = clock.instant().minus(LIFETIME);
        byId.values().removeIf(w -> w.createdAt().isBefore(cutoff));
        if (byId.size() >= MAX_WORKSPACES) {
            byId.values().stream().sorted(Comparator.comparing(Workspace::createdAt)).limit(MAX_WORKSPACES / 10)
                    .map(Workspace::id).toList().forEach(byId::remove);
        }
        grants.values().removeIf(g -> !byId.containsKey(g.workspace().id()));
    }

    int count() {
        return byId.size();
    }

    private String secret(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
