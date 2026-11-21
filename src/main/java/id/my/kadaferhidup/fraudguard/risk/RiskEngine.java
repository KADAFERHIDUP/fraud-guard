package id.my.kadaferhidup.fraudguard.risk;

import static id.my.kadaferhidup.fraudguard.risk.Money.rupiah;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Scores a transaction against the account's recent behaviour. Every rule is a plain check with a fixed
 * number of points and a sentence explaining why, so an analyst (and an auditor) can see exactly how a
 * score was reached. Nothing here is a black box.
 */
public final class RiskEngine {

    public static final ZoneId ZONE = ZoneId.of("Asia/Jakarta");

    public record Result(int score, Outcome outcome, List<Hit> hits, boolean cashReport, boolean ltkmCandidate) {
    }

    private final Thresholds th;

    public RiskEngine(Thresholds th) {
        this.th = th;
    }

    public Thresholds thresholds() {
        return th;
    }

    /** Call with {@code acc.lock} held. Does not change the account; the caller records the result. */
    public Result evaluate(AccountState acc, Txn t, Set<String> watchlist) {
        List<Hit> hits = new ArrayList<>();
        boolean ltkm = false;

        // ---------------------------------------------------------------- hard stops
        if (acc.frozen()) {
            hits.add(Hit.block("ACCOUNT_FROZEN", "Rekening dibekukan setelah kasus fraud dikonfirmasi."));
        }
        if (t.counterpartyAccount() != null && watchlist.contains(t.counterpartyAccount())) {
            hits.add(Hit.block("WATCHLIST", "Rekening lawan " + t.counterpartyAccount() + " ada di daftar pantauan (simulasi DTTOT)."));
            ltkm = true;
        }

        // ---------------------------------------------------------------- amount
        if (t.amount() >= th.largeAbsolute()) {
            hits.add(Hit.of("LARGE_AMOUNT", 15, "Nominal " + rupiah(t.amount()) + " di atas " + rupiah(th.largeAbsolute()) + "."));
        }
        if (t.type().outgoing() && t.amount() >= th.largeVsAverageMin()) {
            List<Long> past = window(acc, t, Duration.ofDays(30)).stream()
                    .filter(r -> r.txn().type().outgoing() && r.outcome() != Outcome.BLOCK).map(r -> r.txn().amount()).toList();
            if (past.size() >= 3) {
                long avg = past.stream().mapToLong(Long::longValue).sum() / past.size();
                if (avg > 0 && t.amount() > avg * th.largeVsAverage()) {
                    hits.add(Hit.of("UNUSUAL_AMOUNT", 25, "Nominal " + (t.amount() / avg) + "× rata-rata keluar 30 hari ("
                            + rupiah(avg) + ")."));
                }
            }
        }

        // ---------------------------------------------------------------- velocity
        if (t.type().outgoing()) {
            long count = window(acc, t, th.velocityWindow()).stream().filter(r -> r.txn().type().outgoing()).count() + 1;
            if (count >= th.velocityHigh()) {
                hits.add(Hit.of("VELOCITY", 40, count + " transaksi keluar dalam " + th.velocityWindow().toMinutes() + " menit."));
            } else if (count >= th.velocityWarn()) {
                hits.add(Hit.of("VELOCITY", 25, count + " transaksi keluar dalam " + th.velocityWindow().toMinutes() + " menit."));
            }
        }

        // ---------------------------------------------------------------- structuring (pemecahan setoran tunai)
        if (t.type() == TxnType.CASH_DEPOSIT && t.amount() < th.cashReport()) {
            List<Long> deposits = new ArrayList<>(window(acc, t, th.structuringWindow()).stream()
                    .filter(r -> r.txn().type() == TxnType.CASH_DEPOSIT && r.outcome() != Outcome.BLOCK && r.txn().amount() < th.cashReport())
                    .map(r -> r.txn().amount()).toList());
            deposits.add(t.amount());
            long sum = deposits.stream().mapToLong(Long::longValue).sum();
            long sizeable = deposits.stream().filter(a -> a >= th.cashReport() / 5).count();
            if (deposits.size() >= th.structuringMinDeposits() && sizeable >= th.structuringMinDeposits() && sum >= th.cashReport()) {
                hits.add(Hit.of("STRUCTURING", 45, deposits.size() + " setoran tunai dalam 24 jam, masing-masing di bawah batas lapor "
                        + rupiah(th.cashReport()) + ", totalnya " + rupiah(sum) + "."));
                ltkm = true;
            } else if (t.amount() >= th.cashReport() * 9 / 10) {
                hits.add(Hit.of("JUST_BELOW_REPORT", 15, "Setoran tunai " + rupiah(t.amount()) + " tepat di bawah batas lapor."));
            }
        }

        // ---------------------------------------------------------------- account and counterparty
        if (t.type().outgoing() && Duration.between(acc.openedAt(), t.at()).compareTo(th.newAccountAge()) < 0
                && t.amount() >= th.newAccountMin()) {
            long days = Math.max(0, Duration.between(acc.openedAt(), t.at()).toDays());
            hits.add(Hit.of("NEW_ACCOUNT", 20, "Rekening baru berumur " + days + " hari mengirim " + rupiah(t.amount()) + "."));
        }
        if (t.type() == TxnType.TRANSFER_OUT && t.amount() >= th.newCounterpartyMin() && !acc.knowsCounterparty(t.counterpartyAccount())) {
            hits.add(Hit.of("NEW_COUNTERPARTY", 15, "Pertama kali mengirim ke " + t.counterpartyAccount() + ", nominal " + rupiah(t.amount()) + "."));
        }
        if (t.deviceId() != null && t.type().outgoing() && t.amount() >= th.newDeviceMin() && acc.hasDevices()
                && !acc.knowsDevice(t.deviceId())) {
            hits.add(Hit.of("NEW_DEVICE", 15, "Perangkat " + t.deviceId() + " belum pernah dipakai rekening ini."));
        }

        // ---------------------------------------------------------------- timing and flow
        int hour = t.at().atZone(ZONE).getHour();
        if (t.type().outgoing() && hour < 4 && t.amount() >= th.oddHourMin()) {
            hits.add(Hit.of("ODD_HOUR", 10, String.format("Transaksi %s pukul %02d.%02d WIB.", rupiah(t.amount()), hour,
                    t.at().atZone(ZONE).getMinute())));
        }
        if (t.type().outgoing()) {
            long in = window(acc, t, th.passThroughWindow()).stream()
                    .filter(r -> r.txn().type().incoming() && r.outcome() != Outcome.BLOCK).mapToLong(r -> r.txn().amount()).sum();
            if (in >= th.passThroughMinIncoming() && t.amount() * 10 >= in * 8) {
                hits.add(Hit.of("PASS_THROUGH", 35, "Dana " + rupiah(in) + " masuk dalam 1 jam terakhir lalu " + rupiah(t.amount())
                        + " langsung keluar, pola rekening penampung."));
                ltkm = true;
            }
            long out30 = window(acc, t, Duration.ofDays(30)).stream()
                    .filter(r -> r.txn().type().outgoing() && r.outcome() != Outcome.BLOCK).mapToLong(r -> r.txn().amount()).sum() + t.amount();
            long income = acc.customer().monthlyIncome();
            if (income > 0 && out30 > income * th.incomeMultiple()) {
                hits.add(Hit.of("INCOME_MISMATCH", 20, "Dana keluar 30 hari " + rupiah(out30) + ", lebih dari " + th.incomeMultiple()
                        + "× penghasilan yang dilaporkan (" + rupiah(income) + "/bulan)."));
            }
            if (acc.customer().rating() == Customer.Rating.HIGH && t.amount() >= th.highRiskMin()) {
                hits.add(Hit.of("HIGH_RISK_CUSTOMER", 10, "Nasabah berprofil risiko tinggi."));
            }
        }

        boolean hard = hits.stream().anyMatch(Hit::hardBlock);
        int score = Math.min(100, hits.stream().mapToInt(Hit::points).sum());
        Outcome outcome = hard || score >= th.blockAt() ? Outcome.BLOCK : score >= th.reviewAt() ? Outcome.REVIEW : Outcome.ALLOW;
        boolean cashReport = t.type().cash() && t.amount() >= th.cashReport() && outcome != Outcome.BLOCK;
        return new Result(score, outcome, List.copyOf(hits), cashReport, ltkm || (outcome != Outcome.ALLOW && score >= th.blockAt()));
    }

    /** The account's transactions in the {@code d} before this one (not after it, not this one). */
    private static List<AccountState.Recorded> window(AccountState acc, Txn t, Duration d) {
        Instant from = t.at().minus(d);
        return acc.between(from, t.at()).stream().filter(r -> !r.txn().id().equals(t.id())).toList();
    }
}
