package id.my.kadaferhidup.fraudguard.risk;

import static id.my.kadaferhidup.fraudguard.risk.Money.JUTA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RiskEngineTest {

    // 2026-11-21 10:00 WIB
    static final Instant NOON = Instant.parse("2026-11-21T03:00:00Z");
    final RiskEngine engine = new RiskEngine(Thresholds.defaults());
    final Customer worker = new Customer("C1", "Ahmad Umraithi", "Karyawan", 8 * JUTA, Customer.Rating.LOW);
    int seq;

    AccountState account(Duration age) {
        return new AccountState("7001", worker, NOON.minus(age));
    }

    Txn txn(TxnType type, long amount, String cp, String device, Instant at) {
        return new Txn("t" + seq++, "7001", type, amount, cp, "mobile", device, at);
    }

    RiskEngine.Result score(AccountState a, Txn t) {
        RiskEngine.Result r = engine.evaluate(a, t, Set.of("8899001122"));
        a.record(t, r.outcome());
        return r;
    }

    static boolean has(RiskEngine.Result r, String rule) {
        return r.hits().stream().anyMatch(h -> h.rule().equals(rule));
    }

    AccountState withHistory() {
        AccountState a = account(Duration.ofDays(700));
        for (int i = 0; i < 10; i++) {
            a.record(txn(TxnType.TRANSFER_OUT, 500_000_00L, "7009000001", "HP-1", NOON.minus(Duration.ofDays(20 - i))), Outcome.ALLOW);
        }
        return a;
    }

    @Test
    void everydayTransferToAKnownPersonPasses() {
        RiskEngine.Result r = score(withHistory(), txn(TxnType.TRANSFER_OUT, 400_000_00L, "7009000001", "HP-1", NOON));
        assertEquals(Outcome.ALLOW, r.outcome());
        assertEquals(0, r.score());
    }

    @Test
    void watchlistedCounterpartyIsBlockedWhateverTheScore() {
        RiskEngine.Result r = score(withHistory(), txn(TxnType.TRANSFER_OUT, 10_000_00L, "8899001122", "HP-1", NOON));
        assertEquals(Outcome.BLOCK, r.outcome());
        assertTrue(has(r, "WATCHLIST"));
        assertTrue(r.ltkmCandidate());
    }

    @Test
    void frozenAccountCannotMoveMoney() {
        AccountState a = withHistory();
        a.freeze();
        assertEquals(Outcome.BLOCK, score(a, txn(TxnType.TRANSFER_OUT, 10_000_00L, "7009000001", "HP-1", NOON)).outcome());
    }

    @Test
    void cashSplitJustUnderTheReportingLineIsStructuring() {
        AccountState a = withHistory();
        assertEquals(Outcome.ALLOW, score(a, txn(TxnType.CASH_DEPOSIT, 190 * JUTA, null, null, NOON)).outcome());
        assertEquals(Outcome.ALLOW, score(a, txn(TxnType.CASH_DEPOSIT, 190 * JUTA, null, null, NOON.plusSeconds(3600))).outcome());
        RiskEngine.Result third = score(a, txn(TxnType.CASH_DEPOSIT, 190 * JUTA, null, null, NOON.plusSeconds(7200)));
        assertTrue(has(third, "STRUCTURING"), third.hits().toString());
        assertEquals(Outcome.REVIEW, third.outcome());
        assertTrue(third.ltkmCandidate());
        assertFalse(third.cashReport(), "tiap setoran di bawah batas, jadi bukan LTKT");
    }

    @Test
    void cashAtTheThresholdIsReportedAutomatically() {
        RiskEngine.Result r = score(withHistory(), txn(TxnType.CASH_DEPOSIT, 500 * JUTA, null, null, NOON));
        assertTrue(r.cashReport());
        assertEquals(Outcome.ALLOW, r.outcome(), "setor tunai besar sah, tetap dilaporkan");
    }

    @Test
    void moneyInThenStraightOutLooksLikeAMuleAccount() {
        AccountState a = account(Duration.ofDays(2));
        score(a, txn(TxnType.TRANSFER_IN, 50 * JUTA, "7009111111", null, NOON));
        RiskEngine.Result out = score(a, txn(TxnType.TRANSFER_OUT, 48 * JUTA, "7009222222", "HP-X", NOON.plusSeconds(600)));
        assertTrue(has(out, "PASS_THROUGH"));
        assertTrue(has(out, "NEW_ACCOUNT"));
        assertEquals(Outcome.BLOCK, out.outcome(), out.hits().toString());
    }

    @Test
    void burstOfTransfersRaisesVelocity() {
        AccountState a = withHistory();
        RiskEngine.Result last = null;
        for (int i = 0; i < 10; i++) {
            last = score(a, txn(TxnType.TRANSFER_OUT, 100_000_00L, "7009000001", "HP-1", NOON.plusSeconds(i * 30L)));
        }
        assertTrue(has(last, "VELOCITY"));
        assertEquals(40, last.score());
        assertEquals(Outcome.REVIEW, last.outcome());
    }

    @Test
    void bigTransferToAStrangerFromANewPhoneAtNightIsBlocked() {
        Instant night = Instant.parse("2026-11-20T19:30:00Z"); // 02.30 WIB
        RiskEngine.Result r = score(withHistory(), txn(TxnType.TRANSFER_OUT, 30 * JUTA, "7009555555", "HP-BARU", night));
        for (String rule : new String[] {"UNUSUAL_AMOUNT", "NEW_COUNTERPARTY", "NEW_DEVICE", "ODD_HOUR", "INCOME_MISMATCH"}) {
            assertTrue(has(r, rule), rule + " " + r.hits());
        }
        assertEquals(Outcome.BLOCK, r.outcome());
    }

    @Test
    void blockedAttemptsDoNotCountAsMoneyThatMoved() {
        AccountState a = account(Duration.ofDays(2));
        RiskEngine.Result r = score(a, txn(TxnType.TRANSFER_IN, 50 * JUTA, "8899001122", null, NOON));
        assertEquals(Outcome.BLOCK, r.outcome());
        RiskEngine.Result out = score(a, txn(TxnType.TRANSFER_OUT, 48 * JUTA, "7009222222", null, NOON.plusSeconds(60)));
        assertFalse(has(out, "PASS_THROUGH"), "dana yang diblokir tidak pernah masuk");
    }

    @Test
    void moneyIsParsedExactlyOrRefused() {
        assertEquals(15_000_000L, Money.parse("150000.00"));
        assertEquals(-1, Money.parse("150000"));
        assertEquals(-1, Money.parse("1.5e5"));
        assertEquals(-1, Money.parse("-10.00"));
        assertEquals(-1, Money.parse(150000));
        assertEquals("Rp12.500.000", Money.rupiah(1_250_000_000L));
    }
}
