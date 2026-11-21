package id.my.kadaferhidup.fraudguard.workspace;

import static id.my.kadaferhidup.fraudguard.risk.Money.JUTA;
import static id.my.kadaferhidup.fraudguard.risk.Money.RUPIAH;

import id.my.kadaferhidup.fraudguard.Problem;
import id.my.kadaferhidup.fraudguard.audit.AuditLog;
import id.my.kadaferhidup.fraudguard.cases.Actor;
import id.my.kadaferhidup.fraudguard.cases.CaseDesk;
import id.my.kadaferhidup.fraudguard.cases.CaseFile;
import id.my.kadaferhidup.fraudguard.cases.Report;
import id.my.kadaferhidup.fraudguard.risk.AccountState;
import id.my.kadaferhidup.fraudguard.risk.Customer;
import id.my.kadaferhidup.fraudguard.risk.Hit;
import id.my.kadaferhidup.fraudguard.risk.Money;
import id.my.kadaferhidup.fraudguard.risk.Outcome;
import id.my.kadaferhidup.fraudguard.risk.RiskEngine;
import id.my.kadaferhidup.fraudguard.risk.Txn;
import id.my.kadaferhidup.fraudguard.risk.TxnType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One isolated bank: its customers, accounts, transactions, cases, reports and audit trail.
 * Every visitor of the public demo gets their own, so nobody sees or changes anyone else's data.
 */
public final class Workspace {

    public static final String OWNER = "Ahmad Umraithi";
    public static final String WATCHLISTED = "8899001122";
    public static final String ACC_EMPLOYEE = "7001002001";
    public static final String ACC_SHOP = "7001002002";
    public static final String ACC_NEW = "7001002003";
    static final int MAX_TRANSACTIONS = 5_000;

    private final String id;
    private final Instant createdAt;
    private final Clock clock;
    private final RiskEngine engine;
    private final Map<String, AccountState> accounts = new LinkedHashMap<>();
    private final Set<String> watchlist = Set.of(WATCHLISTED);
    private final Map<String, String> reserved = new ConcurrentHashMap<>();
    private final Map<String, Decision> decisions = new ConcurrentHashMap<>();
    private final CaseDesk desk;
    private final List<Report> reports = new ArrayList<>();
    private final AtomicInteger ltkt = new AtomicInteger();
    private final AtomicInteger ltkm = new AtomicInteger();
    private final AuditLog audit;
    final Actor analyst;
    final Actor supervisor;
    private static final Actor SYSTEM = new Actor("system", "Sistem", Actor.Role.SYSTEM);

    Workspace(String id, Clock clock, RiskEngine engine) {
        this.id = id;
        this.clock = clock;
        this.engine = engine;
        this.createdAt = clock.instant();
        this.desk = new CaseDesk(clock);
        this.audit = new AuditLog(clock);
        this.analyst = new Actor(id + "-analis", OWNER + " (Analis)", Actor.Role.ANALYST);
        this.supervisor = new Actor(id + "-supervisor", OWNER + " (Supervisor)", Actor.Role.SUPERVISOR);
        seed();
        audit.append(SYSTEM.name(), "WORKSPACE_CREATED", id, "3 nasabah, riwayat 30 hari");
    }

    public String id() {
        return id;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Collection<AccountState> accounts() {
        return accounts.values();
    }

    public CaseDesk desk() {
        return desk;
    }

    public AuditLog audit() {
        return audit;
    }

    public synchronized List<Report> reports() {
        return List.copyOf(reports);
    }

    public Decision decision(String txnId) {
        Decision d = txnId == null ? null : decisions.get(txnId);
        if (d == null) {
            throw Problem.notFound("Transaksi");
        }
        return d;
    }

    public List<Decision> recentDecisions(int n) {
        return decisions.values().stream().sorted((a, b) -> b.evaluatedAt().compareTo(a.evaluatedAt())).limit(n).toList();
    }

    // ------------------------------------------------------------------ scoring

    /**
     * Scores one transaction. The same id with the same content returns the first answer (a retry);
     * the same id with different content is refused, so a decision can never be overwritten.
     */
    public Decision ingest(Txn t) {
        String fp = t.accountNo() + "|" + t.type() + "|" + t.amount() + "|" + t.counterpartyAccount() + "|" + t.at().toEpochMilli();
        String prior = reserved.putIfAbsent(t.id(), fp);
        if (prior != null) {
            Decision d = decisions.get(t.id());
            if (!prior.equals(fp)) {
                throw Problem.conflict("DUPLICATE_ID", "ID transaksi sudah dipakai untuk transaksi lain");
            }
            if (d == null) {
                throw Problem.conflict("IN_PROGRESS", "Transaksi dengan ID ini sedang dinilai");
            }
            return d;
        }
        try {
            if (decisions.size() >= MAX_TRANSACTIONS) {
                throw new Problem(429, "QUOTA", "Kuota transaksi sandbox penuh");
            }
            AccountState acc = accounts.get(t.accountNo());
            if (acc == null) {
                throw Problem.notFound("Rekening");
            }
            Decision d;
            acc.lock.lock();
            try {
                RiskEngine.Result r = engine.evaluate(acc, t, watchlist);
                acc.record(t, r.outcome());
                String caseId = null;
                boolean onlyFrozen = r.hits().stream().allMatch(h -> h.rule().equals("ACCOUNT_FROZEN"));
                if (r.outcome() != Outcome.ALLOW && !onlyFrozen) {
                    caseId = desk.open(t.id(), t.accountNo(), label(acc), t.amount(), r.score(), r.outcome(), r.hits()).id();
                }
                List<String> reportIds = new ArrayList<>();
                if (r.cashReport()) {
                    reportIds.add(report(Report.Type.LTKT, acc, List.of(t.id()), t.amount(),
                            "Transaksi tunai " + Money.rupiah(t.amount()) + " mencapai batas lapor " + Money.rupiah(engine.thresholds().cashReport()),
                            SYSTEM).id());
                }
                d = new Decision(t.id(), t.accountNo(), t.type(), t.amount(), r.score(), r.outcome(), r.outcome(), r.hits(), caseId,
                        List.copyOf(reportIds), clock.instant(), fp);
                decisions.put(t.id(), d);
            } finally {
                acc.lock.unlock();
            }
            audit.append(SYSTEM.name(), "SCORED", t.id(), t.type() + " " + Money.rupiah(t.amount()) + " rek " + t.accountNo()
                    + " skor " + d.score() + " → " + d.outcome() + (d.caseId() == null ? "" : " (" + d.caseId() + ")"));
            return d;
        } catch (RuntimeException e) {
            if (!decisions.containsKey(t.id())) {
                reserved.remove(t.id(), fp);
            }
            throw e;
        }
    }

    // ------------------------------------------------------------------ case actions, with their effects

    public CaseFile claim(String caseId, Actor who) {
        desk.claim(caseId, who);
        audit.append(who.name(), "CLAIM", caseId, null);
        return desk.get(caseId);
    }

    public CaseFile propose(String caseId, Actor who, CaseFile.Resolution r, String note) {
        desk.propose(caseId, who, r, note);
        audit.append(who.name(), "PROPOSE", caseId, r + ": " + note);
        return desk.get(caseId);
    }

    public CaseFile sendBack(String caseId, Actor who, String note) {
        desk.sendBack(caseId, who, note);
        audit.append(who.name(), "SEND_BACK", caseId, note);
        return desk.get(caseId);
    }

    public CaseFile approve(String caseId, Actor who, String note) {
        CaseFile.Resolution r = desk.approve(caseId, who, note);
        CaseFile c = desk.get(caseId);
        AccountState acc = accounts.get(c.accountNo());
        acc.lock.lock();
        try {
            Decision d = decisions.get(c.txnId());
            switch (r) {
                case RELEASE -> {
                    // a held transaction goes through; a blocked one stays blocked (the customer can simply retry)
                    if (d.outcome() == Outcome.REVIEW) {
                        decisions.put(d.txnId(), d.withOutcome(Outcome.ALLOW));
                    }
                }
                case CONFIRM_FRAUD -> {
                    acc.freeze();
                    decisions.put(d.txnId(), d.withOutcome(Outcome.BLOCK));
                }
                case REPORT_LTKM -> {
                    Report rep = report(Report.Type.LTKM, acc, List.of(d.txnId()), d.amount(),
                            "Disetujui " + who.name() + ". " + String.join(" ", c.hits().stream().map(Hit::detail).toList()), who);
                    decisions.put(d.txnId(), d.withOutcome(Outcome.BLOCK).withReport(rep.id()));
                }
                default -> throw new IllegalStateException();
            }
        } finally {
            acc.lock.unlock();
        }
        audit.append(who.name(), "APPROVE", caseId, r + (r == CaseFile.Resolution.CONFIRM_FRAUD ? ", rekening " + acc.accountNo() + " dibekukan" : "")
                + (note == null || note.isBlank() ? "" : ": " + note.strip()));
        return c;
    }

    // ------------------------------------------------------------------ helpers

    private synchronized Report report(Report.Type type, AccountState acc, List<String> txnIds, long total, String reason, Actor by) {
        String rid = String.format("%s-%04d", type, (type == Report.Type.LTKT ? ltkt : ltkm).incrementAndGet());
        Report r = new Report(rid, type, acc.accountNo(), label(acc), txnIds, total, reason, clock.instant(), by.name());
        reports.add(r);
        audit.append(by.name(), "REPORT_" + type, rid, reason);
        return r;
    }

    static String label(AccountState acc) {
        return acc.customer().name() + " · " + acc.customer().label();
    }

    private void seed() {
        Instant now = clock.instant();
        Customer employee = new Customer("C-001", OWNER, "Karyawan swasta", 8 * JUTA, Customer.Rating.LOW);
        Customer shop = new Customer("C-002", OWNER, "Pemilik toko kelontong", 45 * JUTA, Customer.Rating.MEDIUM);
        Customer fresh = new Customer("C-003", OWNER, "Rekening baru", 5 * JUTA, Customer.Rating.MEDIUM);
        AccountState a1 = new AccountState(ACC_EMPLOYEE, employee, now.minus(Duration.ofDays(730)));
        AccountState a2 = new AccountState(ACC_SHOP, shop, now.minus(Duration.ofDays(1100)));
        AccountState a3 = new AccountState(ACC_NEW, fresh, now.minus(Duration.ofDays(2)));
        accounts.put(a1.accountNo(), a1);
        accounts.put(a2.accountNo(), a2);
        accounts.put(a3.accountNo(), a3);

        Random rnd = new Random(42);
        int n = 0;
        // employee: salary, then small everyday transfers to three known people from one phone
        a1.record(new Txn("seed-" + n++, ACC_EMPLOYEE, TxnType.TRANSFER_IN, 8 * JUTA, "7009000099", "payroll", null,
                now.minus(Duration.ofDays(25))), Outcome.ALLOW);
        for (int i = 0; i < 18; i++) {
            long amount = (100_000 + rnd.nextInt(1_400_000)) / 1000 * 1000 * RUPIAH;
            a1.record(new Txn("seed-" + n++, ACC_EMPLOYEE, TxnType.TRANSFER_OUT, amount, "700900000" + (1 + rnd.nextInt(3)), "mobile",
                    "HP-AHMAD-01", now.minus(Duration.ofHours(24L * 28 - i * 36L))), Outcome.ALLOW);
        }
        // shop: daily cash takings, weekly payment to the same supplier
        for (int d = 20; d >= 1; d--) {
            long cash = (2 + rnd.nextInt(5)) * JUTA;
            a2.record(new Txn("seed-" + n++, ACC_SHOP, TxnType.CASH_DEPOSIT, cash, null, "teller", null,
                    now.minus(Duration.ofDays(d)).minus(Duration.ofHours(3))), Outcome.ALLOW);
            if (d % 7 == 0) {
                a2.record(new Txn("seed-" + n++, ACC_SHOP, TxnType.TRANSFER_OUT, (10 + rnd.nextInt(10)) * JUTA, "7009000010", "internet",
                        "PC-TOKO-01", now.minus(Duration.ofDays(d)).minus(Duration.ofHours(1))), Outcome.ALLOW);
            }
        }
    }
}
