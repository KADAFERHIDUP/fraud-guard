package id.my.kadaferhidup.fraudguard.risk;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * What the engine remembers about one account: the last 30 days of transactions with their outcome,
 * the devices and counterparties seen before, and whether the account is frozen.
 * Read and changed only while {@link #lock} is held, so parallel transactions are scored one at a time
 * and a burst cannot slip past the velocity rule.
 */
public final class AccountState {

    public record Recorded(Txn txn, Outcome outcome) {
    }

    static final Duration KEEP = Duration.ofDays(30);
    static final int MAX_HISTORY = 5_000;

    private final String accountNo;
    private final Customer customer;
    private final Instant openedAt;
    private final Deque<Recorded> history = new ArrayDeque<>();
    private final Set<String> devices = new HashSet<>();
    private final Set<String> counterparties = new HashSet<>();
    private volatile boolean frozen;
    public final ReentrantLock lock = new ReentrantLock();

    public AccountState(String accountNo, Customer customer, Instant openedAt) {
        this.accountNo = accountNo;
        this.customer = customer;
        this.openedAt = openedAt;
    }

    public String accountNo() {
        return accountNo;
    }

    public Customer customer() {
        return customer;
    }

    public Instant openedAt() {
        return openedAt;
    }

    public boolean frozen() {
        return frozen;
    }

    public void freeze() {
        frozen = true;
    }

    /** Remembers a scored transaction. Blocked ones count as attempts but never as money that moved. */
    public void record(Txn t, Outcome outcome) {
        history.addLast(new Recorded(t, outcome));
        while (history.size() > MAX_HISTORY) {
            history.removeFirst();
        }
        Instant cutoff = t.at().minus(KEEP);
        history.removeIf(r -> r.txn().at().isBefore(cutoff));
        if (outcome != Outcome.BLOCK) {
            if (t.deviceId() != null) {
                devices.add(t.deviceId());
            }
            if (t.counterpartyAccount() != null && t.type() == TxnType.TRANSFER_OUT) {
                counterparties.add(t.counterpartyAccount());
            }
        }
    }

    /** Transactions with time in [from, to]. */
    List<Recorded> between(Instant from, Instant to) {
        return history.stream().filter(r -> !r.txn().at().isBefore(from) && !r.txn().at().isAfter(to)).toList();
    }

    boolean knowsDevice(String d) {
        return devices.contains(d);
    }

    boolean hasDevices() {
        return !devices.isEmpty();
    }

    boolean knowsCounterparty(String a) {
        return counterparties.contains(a);
    }
}
