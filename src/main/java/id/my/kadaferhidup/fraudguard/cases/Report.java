package id.my.kadaferhidup.fraudguard.cases;

import java.time.Instant;
import java.util.List;

/**
 * A report for PPATK. LTKT (cash at or above the threshold) is generated automatically; LTKM (suspicious
 * transaction) is drafted when a case is closed with that resolution, after a supervisor approves it.
 */
public record Report(String id, Type type, String accountNo, String customer, List<String> txnIds, long total, String reason,
                     Instant createdAt, String createdBy) {

    public enum Type { LTKT, LTKM }
}
