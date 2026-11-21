package id.my.kadaferhidup.fraudguard.audit;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Append-only audit trail. Each entry carries the SHA-256 of the previous one, so editing, deleting or
 * reordering any past entry breaks every hash after it, and {@link #verify()} shows where.
 */
public final class AuditLog {

    public record Entry(long seq, Instant at, String actor, String action, String subject, String detail, String prevHash,
                        String hash) {
    }

    public record Verification(boolean intact, long entries, Long brokenAt) {
    }

    static final String GENESIS = "0".repeat(64);
    static final int MAX_ENTRIES = 50_000;

    private final List<Entry> entries = new ArrayList<>();
    private final Clock clock;

    public AuditLog(Clock clock) {
        this.clock = clock;
    }

    public synchronized Entry append(String actor, String action, String subject, String detail) {
        if (entries.size() >= MAX_ENTRIES) {
            throw new IllegalStateException("audit penuh");
        }
        long seq = entries.size() + 1L;
        String prev = entries.isEmpty() ? GENESIS : entries.get(entries.size() - 1).hash();
        Instant at = clock.instant();
        Entry e = new Entry(seq, at, actor, action, subject, detail, prev, hash(seq, at, actor, action, subject, detail, prev));
        entries.add(e);
        return e;
    }

    public synchronized List<Entry> latest(int n) {
        return List.copyOf(entries.subList(Math.max(0, entries.size() - n), entries.size()));
    }

    public synchronized Verification verify() {
        String prev = GENESIS;
        for (Entry e : entries) {
            String expect = hash(e.seq(), e.at(), e.actor(), e.action(), e.subject(), e.detail(), prev);
            if (!e.prevHash().equals(prev) || !e.hash().equals(expect)) {
                return new Verification(false, entries.size(), e.seq());
            }
            prev = e.hash();
        }
        return new Verification(true, entries.size(), null);
    }

    /** Only for tests: simulates someone editing the stored log directly. */
    synchronized void tamper(int index, String newDetail) {
        Entry e = entries.get(index);
        entries.set(index, new Entry(e.seq(), e.at(), e.actor(), e.action(), e.subject(), newDetail, e.prevHash(), e.hash()));
    }

    private static String hash(long seq, Instant at, String actor, String action, String subject, String detail, String prev) {
        String s = String.join("\u001f", String.valueOf(seq), at.toString(), actor, action, subject, detail == null ? "" : detail, prev);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
