package id.my.kadaferhidup.fraudguard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import id.my.kadaferhidup.fraudguard.json.Json;
import id.my.kadaferhidup.fraudguard.workspace.Workspace;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The real server on a random port, used the way the core banking system and the analysts would. */
class AppTest {

    App app;
    final HttpClient http = HttpClient.newHttpClient();
    int seq;

    record Res(int status, Map<String, Object> body) {
        Object get(String k) {
            return body.get(k);
        }
    }

    record Sandbox(String api, String analyst, String supervisor) {
    }

    @BeforeEach
    void start() throws Exception {
        app = App.start(new App.Config(0, 10_000, 1_000), new MutableClock(Instant.now()));
    }

    @AfterEach
    void stop() {
        app.close();
    }

    Res call(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), Json.parseObject(r.body()));
    }

    Sandbox sandbox() throws Exception {
        Res r = call("POST", "/api/sandbox", null, "{}");
        assertEquals(201, r.status(), String.valueOf(r.body()));
        return new Sandbox((String) r.get("apiKey"), (String) r.get("analystToken"), (String) r.get("supervisorToken"));
    }

    String txn(String account, String type, String amount, String cp, String device) {
        return "{\"id\":\"T" + (seq++) + "\",\"accountNo\":\"" + account + "\",\"type\":\"" + type + "\",\"amount\":\"" + amount + "\""
                + (cp == null ? "" : ",\"counterpartyAccount\":\"" + cp + "\"") + (device == null ? "" : ",\"deviceId\":\"" + device + "\"")
                + ",\"channel\":\"mobile\"}";
    }

    Res send(Sandbox s, String body) throws Exception {
        return call("POST", "/api/transactions", s.api(), body);
    }

    // ------------------------------------------------------------------ scoring

    @Test
    void normalTransferPassesAndAMuleTransferIsBlockedWithReasons() throws Exception {
        Sandbox s = sandbox();
        Res ok = send(s, txn(Workspace.ACC_EMPLOYEE, "TRANSFER_OUT", "350000.00", "7009000001", "HP-AHMAD-01"));
        assertEquals("ALLOW", ok.get("decision"), String.valueOf(ok.body()));

        assertEquals("ALLOW", send(s, txn(Workspace.ACC_NEW, "TRANSFER_IN", "50000000.00", "7009111111", null)).get("decision"));
        Res mule = send(s, txn(Workspace.ACC_NEW, "TRANSFER_OUT", "48000000.00", "7009222222", "HP-X"));
        assertEquals("BLOCK", mule.get("decision"));
        assertTrue(String.valueOf(mule.get("reasons")).contains("PASS_THROUGH"));
        assertTrue(((String) mule.get("caseId")).startsWith("CASE-"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void bigCashIsReportedAsLtkt() throws Exception {
        Sandbox s = sandbox();
        Res r = send(s, txn(Workspace.ACC_SHOP, "CASH_DEPOSIT", "600000000.00", null, null));
        assertEquals(1, ((List<Object>) r.get("reports")).size());
        Res reports = call("GET", "/api/reports", s.analyst(), null);
        assertTrue(String.valueOf(reports.body()).contains("LTKT-0001"));
    }

    @Test
    void sameIdIsAnsweredOnceAndCannotBeReusedForAnotherTransaction() throws Exception {
        Sandbox s = sandbox();
        String body = "{\"id\":\"DUP-1\",\"accountNo\":\"" + Workspace.ACC_EMPLOYEE + "\",\"type\":\"TRANSFER_OUT\",\"amount\":\"100000.00\","
                + "\"counterpartyAccount\":\"7009000001\",\"at\":\"" + java.time.OffsetDateTime.now().withNano(0) + "\"}";
        Res a = send(s, body);
        Res b = send(s, body);
        assertEquals(a.get("evaluatedAt"), b.get("evaluatedAt"));
        Res c = send(s, body.replace("100000.00", "900000.00"));
        assertEquals(409, c.status());
        assertEquals("DUPLICATE_ID", c.get("error"));
    }

    @Test
    void parallelBurstIsCountedExactly() throws Exception {
        Sandbox s = sandbox();
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Res>> fs = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String body = txn(Workspace.ACC_EMPLOYEE, "TRANSFER_OUT", "100000.00", "7009000001", "HP-AHMAD-01");
            fs.add(pool.submit(() -> {
                go.await();
                return send(s, body);
            }));
        }
        go.countDown();
        int review = 0;
        for (Future<Res> f : fs) {
            if ("REVIEW".equals(f.get().get("decision"))) {
                review++;
            }
        }
        pool.shutdown();
        // transfers 10, 11 and 12 of the burst cross the high velocity line, no matter how they interleave
        assertEquals(3, review);
    }

    @Test
    void badInputIsRefusedWithTheField() throws Exception {
        Sandbox s = sandbox();
        Res r = send(s, txn(Workspace.ACC_EMPLOYEE, "TRANSFER_OUT", "1e9", "7009000001", null));
        assertEquals(422, r.status());
        assertTrue(String.valueOf(r.get("message")).startsWith("amount"));
        assertEquals(422, send(s, txn(Workspace.ACC_EMPLOYEE, "TRANSFER_OUT", "10.00", null, null)).status(), "transfer tanpa lawan");
        assertEquals(404, send(s, txn("1234567890", "TRANSFER_OUT", "10.00", "7009000001", null)).status());
        assertEquals(422, send(s, txn(Workspace.ACC_EMPLOYEE, "TRANSFER_OUT", "10.00", Workspace.ACC_EMPLOYEE, null)).status());
        assertEquals(422, send(s, "{\"id\":\"<x>\",\"accountNo\":\"7001002001\",\"type\":\"CASH_DEPOSIT\",\"amount\":\"10.00\"}").status());
    }

    // ------------------------------------------------------------------ who may do what

    @Test
    void eachCredentialHasOnlyItsOwnPower() throws Exception {
        Sandbox s = sandbox();
        assertEquals(403, call("GET", "/api/cases", s.api(), null).status(), "API key tidak membaca kasus");
        assertEquals(403, send(new Sandbox(s.analyst(), null, null), txn(Workspace.ACC_EMPLOYEE, "CASH_DEPOSIT", "10.00", null, null)).status(),
                "analis tidak menyuntik transaksi");
        assertEquals(403, call("GET", "/api/audit", s.analyst(), null).status(), "audit hanya supervisor");
        assertEquals(200, call("GET", "/api/audit", s.supervisor(), null).status());
        assertEquals(401, call("GET", "/api/cases", "tebak-token", null).status());
        assertEquals(401, call("GET", "/api/cases", null, null).status());
    }

    @Test
    void oneSandboxCannotSeeAnothersCases() throws Exception {
        Sandbox a = sandbox();
        Sandbox b = sandbox();
        send(a, txn(Workspace.ACC_NEW, "TRANSFER_IN", "50000000.00", "7009111111", null));
        send(a, txn(Workspace.ACC_NEW, "TRANSFER_OUT", "48000000.00", "7009222222", null));
        assertEquals(200, call("GET", "/api/cases/CASE-0001", a.analyst(), null).status());
        assertEquals(404, call("GET", "/api/cases/CASE-0001", b.analyst(), null).status());
        assertEquals(404, call("POST", "/api/cases/CASE-0001/claim", b.analyst(), "{}").status(), "klaim kasus sandbox lain");
    }

    @Test
    void fourEyesWorkflowFreezesTheAccountAndTheAuditChainHolds() throws Exception {
        Sandbox s = sandbox();
        send(s, txn(Workspace.ACC_NEW, "TRANSFER_IN", "50000000.00", "7009111111", null));
        String caseId = (String) send(s, txn(Workspace.ACC_NEW, "TRANSFER_OUT", "48000000.00", "7009222222", null)).get("caseId");
        String base = "/api/cases/" + caseId;

        assertEquals(403, call("POST", base + "/claim", s.supervisor(), "{}").status());
        assertEquals(200, call("POST", base + "/claim", s.analyst(), "{}").status());
        assertEquals(200, call("POST", base + "/propose", s.analyst(),
                "{\"resolution\":\"CONFIRM_FRAUD\",\"note\":\"Dana masuk langsung keluar ke rekening baru\"}").status());
        assertEquals(403, call("POST", base + "/approve", s.analyst(), "{}").status(), "analis menyetujui sendiri");
        Res ok = call("POST", base + "/approve", s.supervisor(), "{\"note\":\"Setuju, bekukan\"}");
        assertEquals("CLOSED", ok.get("status"));

        Res after = send(s, txn(Workspace.ACC_NEW, "TRANSFER_OUT", "10000.00", "7009000001", null));
        assertEquals("BLOCK", after.get("decision"));
        assertTrue(String.valueOf(after.get("reasons")).contains("ACCOUNT_FROZEN"));

        Res audit = call("GET", "/api/audit", s.supervisor(), null);
        assertEquals(Boolean.TRUE, audit.get("intact"));
        assertTrue(String.valueOf(audit.body()).contains("APPROVE"));
    }

    @Test
    void ltkmIsOnlyCreatedAfterApproval() throws Exception {
        Sandbox s = sandbox();
        for (int i = 0; i < 3; i++) {
            send(s, txn(Workspace.ACC_SHOP, "CASH_DEPOSIT", "190000000.00", null, null));
        }
        Res cases = call("GET", "/api/cases?status=OPEN", s.analyst(), null);
        assertTrue(String.valueOf(cases.body()).contains("CASE-0001"), String.valueOf(cases.body()));
        String base = "/api/cases/CASE-0001";
        call("POST", base + "/claim", s.analyst(), "{}");
        call("POST", base + "/propose", s.analyst(), "{\"resolution\":\"REPORT_LTKM\",\"note\":\"Setoran dipecah di bawah batas lapor\"}");
        assertFalse(String.valueOf(call("GET", "/api/reports", s.analyst(), null).body()).contains("LTKM"));
        call("POST", base + "/approve", s.supervisor(), "{}");
        assertTrue(String.valueOf(call("GET", "/api/reports", s.analyst(), null).body()).contains("LTKM-0001"));
    }

    @Test
    void sandboxCreationIsRateLimitedPerIp() throws Exception {
        app.close();
        app = App.start(new App.Config(0, 10_000, 2), new MutableClock(Instant.now()));
        sandbox();
        sandbox();
        assertEquals(429, call("POST", "/api/sandbox", null, "{}").status());
    }
}
