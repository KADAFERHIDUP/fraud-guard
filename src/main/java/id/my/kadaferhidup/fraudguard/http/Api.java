package id.my.kadaferhidup.fraudguard.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import id.my.kadaferhidup.fraudguard.Problem;
import id.my.kadaferhidup.fraudguard.audit.AuditLog;
import id.my.kadaferhidup.fraudguard.cases.Actor;
import id.my.kadaferhidup.fraudguard.cases.CaseFile;
import id.my.kadaferhidup.fraudguard.cases.Report;
import id.my.kadaferhidup.fraudguard.json.Json;
import id.my.kadaferhidup.fraudguard.risk.AccountState;
import id.my.kadaferhidup.fraudguard.risk.Hit;
import id.my.kadaferhidup.fraudguard.risk.Money;
import id.my.kadaferhidup.fraudguard.risk.Txn;
import id.my.kadaferhidup.fraudguard.risk.TxnType;
import id.my.kadaferhidup.fraudguard.workspace.Decision;
import id.my.kadaferhidup.fraudguard.workspace.Workspace;
import id.my.kadaferhidup.fraudguard.workspace.Workspaces;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** HTTP routing, authentication and input validation. Business rules live in the workspace and the engine. */
public final class Api implements HttpHandler {

    private static final Logger LOG = Logger.getLogger(Api.class.getName());
    private static final int MAX_BODY = 8 * 1024;
    private static final Pattern CASE_PATH = Pattern.compile("^/api/cases/(CASE-[0-9]{4})(?:/(claim|propose|approve|send-back))?$");
    private static final Pattern TXN_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern ACCOUNT = Pattern.compile("^[0-9]{6,20}$");
    private static final Pattern CHANNEL = Pattern.compile("^[a-z_]{1,20}$");
    private static final Pattern DEVICE = Pattern.compile("^[A-Za-z0-9_-]{1,40}$");
    static final long MAX_AMOUNT = 10_000_000_000_000L; // Rp100 miliar in sen

    private final Workspaces workspaces;
    private final RateLimiter perIp;
    private final RateLimiter sandboxLimit;
    private final Clock clock;
    private final StaticFiles files = new StaticFiles();

    public Api(Workspaces workspaces, RateLimiter perIp, RateLimiter sandboxLimit, Clock clock) {
        this.workspaces = workspaces;
        this.perIp = perIp;
        this.sandboxLimit = sandboxLimit;
        this.clock = clock;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try (ex) {
            securityHeaders(ex);
            String path = ex.getRequestURI().getRawPath();
            String method = ex.getRequestMethod();
            try {
                if (!path.startsWith("/api/")) {
                    if (!method.equals("GET") && !method.equals("HEAD")) {
                        throw new Problem(405, "METHOD_NOT_ALLOWED", "Metode tidak didukung");
                    }
                    files.serve(ex, path);
                    return;
                }
                if (path.equals("/api/health")) {
                    json(ex, 200, Map.of("status", "ok"));
                    return;
                }
                long wait = perIp.acquire(Http.clientIp(ex));
                if (wait > 0) {
                    ex.getResponseHeaders().set("Retry-After", String.valueOf(wait));
                    throw new Problem(429, "RATE_LIMITED", "Terlalu banyak permintaan. Coba lagi " + wait + " detik lagi.");
                }
                route(ex, method, path);
            } catch (Problem p) {
                json(ex, p.status(), Map.of("error", p.code(), "message", p.getMessage()));
            } catch (RuntimeException e) {
                LOG.log(Level.SEVERE, "kesalahan tak terduga", e);
                json(ex, 500, Map.of("error", "INTERNAL", "message", "Terjadi kesalahan di server"));
            }
        }
    }

    private void route(HttpExchange ex, String method, String path) throws IOException {
        if (path.equals("/api/sandbox") && method.equals("POST")) {
            sandbox(ex);
            return;
        }
        Workspaces.Grant g = workspaces.authenticate(ex.getRequestHeaders().getFirst("Authorization"));
        Workspace w = g.workspace();
        if (path.equals("/api/transactions") && method.equals("POST")) {
            if (!g.ingest()) {
                throw Problem.forbidden("Hanya API key sistem core banking yang boleh mengirim transaksi");
            }
            json(ex, 200, decision(w.ingest(parseTxn(body(ex)))));
            return;
        }
        Actor who = g.actor();
        if (who == null) {
            throw Problem.forbidden("API key hanya untuk mengirim transaksi, bukan untuk membaca kasus");
        }
        if (path.equals("/api/transactions") && method.equals("GET")) {
            json(ex, 200, Map.of("items", w.recentDecisions(30).stream().map(Api::decision).toList()));
        } else if (path.equals("/api/accounts") && method.equals("GET")) {
            json(ex, 200, Map.of("items", w.accounts().stream().map(Api::account).toList()));
        } else if (path.equals("/api/cases") && method.equals("GET")) {
            CaseFile.Status st = enumOf(CaseFile.Status.class, query(ex, "status"), "status");
            json(ex, 200, Map.of("items", w.desk().list(st).stream().map(c -> caseView(c, false)).toList()));
        } else if (path.equals("/api/reports") && method.equals("GET")) {
            json(ex, 200, Map.of("items", w.reports().stream().map(Api::report).toList()));
        } else if (path.equals("/api/audit") && method.equals("GET")) {
            if (who.role() != Actor.Role.SUPERVISOR) {
                throw Problem.forbidden("Log audit hanya untuk supervisor");
            }
            AuditLog.Verification v = w.audit().verify();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("intact", v.intact());
            out.put("entries", v.entries());
            out.put("brokenAt", v.brokenAt());
            out.put("items", w.audit().latest(100).reversed());
            json(ex, 200, out);
        } else {
            Matcher m = CASE_PATH.matcher(path);
            if (!m.matches()) {
                throw Problem.notFound("Alamat");
            }
            String id = m.group(1);
            String action = m.group(2);
            if (action == null) {
                if (!method.equals("GET")) {
                    throw new Problem(405, "METHOD_NOT_ALLOWED", "Metode tidak didukung");
                }
                json(ex, 200, caseView(w.desk().get(id), true));
                return;
            }
            if (!method.equals("POST")) {
                throw new Problem(405, "METHOD_NOT_ALLOWED", "Metode tidak didukung");
            }
            Map<String, Object> b = body(ex);
            String note = b.get("note") instanceof String s ? s : null;
            CaseFile c = switch (action) {
                case "claim" -> w.claim(id, who);
                case "propose" -> w.propose(id, who, enumOf(CaseFile.Resolution.class, b.get("resolution"), "resolution"), note);
                case "approve" -> w.approve(id, who, note);
                case "send-back" -> w.sendBack(id, who, note);
                default -> throw Problem.notFound("Alamat");
            };
            json(ex, 200, caseView(c, true));
        }
    }

    // ------------------------------------------------------------------ sandbox

    private void sandbox(HttpExchange ex) throws IOException {
        long wait = sandboxLimit.acquire(Http.clientIp(ex));
        if (wait > 0) {
            ex.getResponseHeaders().set("Retry-After", String.valueOf(wait));
            throw new Problem(429, "RATE_LIMITED", "Terlalu banyak sandbox dari alamat ini. Coba lagi " + wait + " detik lagi.");
        }
        Workspaces.Credentials c = workspaces.create();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workspace", c.workspace().id());
        out.put("apiKey", c.apiKey());
        out.put("analystToken", c.analystToken());
        out.put("supervisorToken", c.supervisorToken());
        out.put("accounts", c.workspace().accounts().stream().map(Api::account).toList());
        out.put("watchlistAccount", Workspace.WATCHLISTED);
        out.put("expiresAt", c.workspace().createdAt().plus(Duration.ofHours(24)).toString());
        json(ex, 201, out);
    }

    // ------------------------------------------------------------------ input

    private Txn parseTxn(Map<String, Object> b) {
        String id = text(b, "id", TXN_ID, true);
        String accountNo = text(b, "accountNo", ACCOUNT, true);
        TxnType type = enumOf(TxnType.class, b.get("type"), "type");
        if (type == null) {
            throw Problem.invalid("type", "wajib diisi");
        }
        long amount = Money.parse(b.get("amount"));
        if (amount <= 0 || amount > MAX_AMOUNT) {
            throw Problem.invalid("amount", "string dua desimal, contoh \"1500000.00\", lebih dari 0 dan maksimal Rp100 miliar");
        }
        String cp = text(b, "counterpartyAccount", ACCOUNT, type.transfer());
        if (cp != null && cp.equals(accountNo)) {
            throw Problem.invalid("counterpartyAccount", "tidak boleh sama dengan rekening sendiri");
        }
        String channel = text(b, "channel", CHANNEL, false);
        String device = text(b, "deviceId", DEVICE, false);
        Instant now = clock.instant();
        Instant at = now;
        if (b.get("at") != null) {
            try {
                at = OffsetDateTime.parse(String.valueOf(b.get("at"))).toInstant();
            } catch (DateTimeParseException e) {
                throw Problem.invalid("at", "format ISO-8601 dengan zona waktu, contoh 2026-11-21T02:15:00+07:00");
            }
            if (at.isAfter(now.plus(Duration.ofMinutes(5))) || at.isBefore(now.minus(Duration.ofDays(30)))) {
                throw Problem.invalid("at", "harus dalam 30 hari terakhir dan tidak di masa depan");
            }
        }
        return new Txn(id, accountNo, type, amount, cp, channel == null ? "api" : channel, device, at);
    }

    private static String text(Map<String, Object> b, String field, Pattern p, boolean required) {
        Object v = b.get(field);
        if (v == null || (v instanceof String s && s.isBlank())) {
            if (required) {
                throw Problem.invalid(field, "wajib diisi");
            }
            return null;
        }
        if (!(v instanceof String s) || !p.matcher(s).matches()) {
            throw Problem.invalid(field, "format tidak valid");
        }
        return s;
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, Object v, String field) {
        if (v == null || "".equals(v)) {
            return null;
        }
        try {
            return Enum.valueOf(type, String.valueOf(v));
        } catch (IllegalArgumentException e) {
            throw Problem.invalid(field, "nilai tidak dikenal");
        }
    }

    private static String query(HttpExchange ex, String name) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) {
            return null;
        }
        for (String part : q.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(name)) {
                return part.substring(eq + 1);
            }
        }
        return null;
    }

    private static Map<String, Object> body(HttpExchange ex) {
        String type = ex.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.toLowerCase().startsWith("application/json")) {
            throw new Problem(415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type harus application/json");
        }
        try (InputStream in = ex.getRequestBody()) {
            byte[] raw = in.readNBytes(MAX_BODY + 1);
            if (raw.length > MAX_BODY) {
                throw new Problem(413, "TOO_LARGE", "Body maksimal 8 KB");
            }
            String s = new String(raw, StandardCharsets.UTF_8);
            return Json.parseObject(s.isBlank() ? "{}" : s);
        } catch (IOException e) {
            throw new Problem(400, "BAD_REQUEST", "Body tidak bisa dibaca");
        } catch (Json.JsonException e) {
            throw new Problem(400, "BAD_REQUEST", "Body bukan JSON yang valid");
        }
    }

    // ------------------------------------------------------------------ output

    static Map<String, Object> decision(Decision d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.txnId());
        m.put("accountNo", d.accountNo());
        m.put("type", d.type());
        m.put("amount", Money.format(d.amount()));
        m.put("score", d.score());
        m.put("decision", d.outcome());
        m.put("initialDecision", d.initialOutcome());
        m.put("reasons", d.hits().stream().map(Api::hit).toList());
        m.put("caseId", d.caseId());
        m.put("reports", d.reportIds());
        m.put("evaluatedAt", d.evaluatedAt());
        return m;
    }

    static Map<String, Object> hit(Hit h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rule", h.rule());
        m.put("points", h.points());
        m.put("detail", h.detail());
        m.put("hardBlock", h.hardBlock());
        return m;
    }

    static Map<String, Object> account(AccountState a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accountNo", a.accountNo());
        m.put("customerId", a.customer().id());
        m.put("name", a.customer().name());
        m.put("label", a.customer().label());
        m.put("monthlyIncome", Money.format(a.customer().monthlyIncome()));
        m.put("rating", a.customer().rating());
        m.put("openedAt", a.openedAt());
        m.put("frozen", a.frozen());
        return m;
    }

    static Map<String, Object> caseView(CaseFile c, boolean full) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());
        m.put("status", c.status());
        m.put("txnId", c.txnId());
        m.put("accountNo", c.accountNo());
        m.put("customer", c.customer());
        m.put("amount", Money.format(c.amount()));
        m.put("score", c.score());
        m.put("decision", c.outcome());
        m.put("assignee", c.assignee());
        m.put("proposal", c.proposal());
        m.put("proposedBy", c.proposedBy());
        m.put("resolution", c.resolution());
        m.put("approvedBy", c.approvedBy());
        m.put("createdAt", c.createdAt());
        if (full) {
            m.put("reasons", c.hits().stream().map(Api::hit).toList());
            m.put("steps", c.steps().stream().map(s -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("at", s.at());
                x.put("actor", s.actor());
                x.put("action", s.action());
                x.put("note", s.note());
                return x;
            }).toList());
        }
        return m;
    }

    static Map<String, Object> report(Report r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("type", r.type());
        m.put("accountNo", r.accountNo());
        m.put("customer", r.customer());
        m.put("transactions", r.txnIds());
        m.put("total", Money.format(r.total()));
        m.put("reason", r.reason());
        m.put("createdAt", r.createdAt());
        m.put("createdBy", r.createdBy());
        return m;
    }

    static void json(HttpExchange ex, int status, Object value) throws IOException {
        byte[] out = Json.write(value).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        boolean head = ex.getRequestMethod().equals("HEAD");
        ex.sendResponseHeaders(status, head ? -1 : out.length);
        if (!head) {
            ex.getResponseBody().write(out);
        }
    }

    private static void securityHeaders(HttpExchange ex) {
        var h = ex.getResponseHeaders();
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Referrer-Policy", "no-referrer");
        h.set("X-Frame-Options", "DENY");
        h.set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' https://fonts.googleapis.com; "
                + "font-src https://fonts.gstatic.com; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; "
                + "base-uri 'none'; form-action 'none'");
    }
}
