package id.my.kadaferhidup.fraudguard.workspace;

import id.my.kadaferhidup.fraudguard.risk.Hit;
import id.my.kadaferhidup.fraudguard.risk.Outcome;
import id.my.kadaferhidup.fraudguard.risk.TxnType;
import java.time.Instant;
import java.util.List;

/**
 * The answer for one transaction. {@code outcome} can change once, when an analyst's case is approved
 * (a held transaction released or kept); {@code initialOutcome} never changes.
 */
public record Decision(String txnId, String accountNo, TxnType type, long amount, int score, Outcome initialOutcome,
                       Outcome outcome, List<Hit> hits, String caseId, List<String> reportIds, Instant evaluatedAt,
                       String fingerprint) {

    Decision withOutcome(Outcome o) {
        return new Decision(txnId, accountNo, type, amount, score, initialOutcome, o, hits, caseId, reportIds, evaluatedAt, fingerprint);
    }

    Decision withReport(String reportId) {
        List<String> r = new java.util.ArrayList<>(reportIds);
        r.add(reportId);
        return new Decision(txnId, accountNo, type, amount, score, initialOutcome, outcome, hits, caseId, List.copyOf(r), evaluatedAt, fingerprint);
    }
}
