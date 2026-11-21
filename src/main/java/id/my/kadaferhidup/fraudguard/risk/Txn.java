package id.my.kadaferhidup.fraudguard.risk;

import java.time.Instant;

/** One transaction as the core banking system reports it. Amounts are in sen (Rp1 = 100). */
public record Txn(String id, String accountNo, TxnType type, long amount, String counterpartyAccount, String channel,
                  String deviceId, Instant at) {
}
