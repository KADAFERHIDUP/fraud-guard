package id.my.kadaferhidup.fraudguard.cases;

import id.my.kadaferhidup.fraudguard.risk.Hit;
import id.my.kadaferhidup.fraudguard.risk.Outcome;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * An alert an analyst must decide. State changes only through {@link CaseDesk}, under this object's lock.
 * OPEN → IN_REVIEW (claimed) → PENDING_APPROVAL (resolution proposed) → CLOSED (a different person approved).
 */
public final class CaseFile {

    public enum Status { OPEN, IN_REVIEW, PENDING_APPROVAL, CLOSED }

    public enum Resolution { RELEASE, CONFIRM_FRAUD, REPORT_LTKM }

    public record Step(Instant at, String actor, String action, String note) {
    }

    final String id;
    final String txnId;
    final String accountNo;
    final String customer;
    final long amount;
    final int score;
    final Outcome outcome;
    final List<Hit> hits;
    final Instant createdAt;
    Status status = Status.OPEN;
    String assignee;
    Resolution proposal;
    String proposedBy;
    Resolution resolution;
    String approvedBy;
    final List<Step> steps = new ArrayList<>();

    CaseFile(String id, String txnId, String accountNo, String customer, long amount, int score, Outcome outcome, List<Hit> hits,
             Instant createdAt) {
        this.id = id;
        this.txnId = txnId;
        this.accountNo = accountNo;
        this.customer = customer;
        this.amount = amount;
        this.score = score;
        this.outcome = outcome;
        this.hits = hits;
        this.createdAt = createdAt;
    }

    public String id() {
        return id;
    }

    public String txnId() {
        return txnId;
    }

    public String accountNo() {
        return accountNo;
    }

    public String customer() {
        return customer;
    }

    public long amount() {
        return amount;
    }

    public int score() {
        return score;
    }

    public Outcome outcome() {
        return outcome;
    }

    public List<Hit> hits() {
        return hits;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public synchronized Status status() {
        return status;
    }

    public synchronized String assignee() {
        return assignee;
    }

    public synchronized Resolution proposal() {
        return proposal;
    }

    public synchronized String proposedBy() {
        return proposedBy;
    }

    public synchronized Resolution resolution() {
        return resolution;
    }

    public synchronized String approvedBy() {
        return approvedBy;
    }

    public synchronized List<Step> steps() {
        return List.copyOf(steps);
    }
}
