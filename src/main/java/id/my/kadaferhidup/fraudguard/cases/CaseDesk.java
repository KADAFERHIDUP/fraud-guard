package id.my.kadaferhidup.fraudguard.cases;

import id.my.kadaferhidup.fraudguard.Problem;
import id.my.kadaferhidup.fraudguard.risk.Hit;
import id.my.kadaferhidup.fraudguard.risk.Outcome;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The case workflow and its rules:
 * <ul>
 *   <li>only an analyst can claim and propose, only the analyst holding the case can propose;</li>
 *   <li>only a supervisor can approve or send back, and never a proposal they made themselves (four eyes);</li>
 *   <li>every step happens under the case's lock and checks the current state, so two people clicking at the
 *       same time cannot both win.</li>
 * </ul>
 */
public final class CaseDesk {

    static final int MAX_CASES = 5_000;
    static final int MAX_NOTE = 500;

    private final Map<String, CaseFile> cases = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger();
    private final Clock clock;

    public CaseDesk(Clock clock) {
        this.clock = clock;
    }

    public CaseFile open(String txnId, String accountNo, String customer, long amount, int score, Outcome outcome, List<Hit> hits) {
        if (cases.size() >= MAX_CASES) {
            throw new Problem(429, "QUOTA", "Kuota kasus sandbox penuh");
        }
        String id = String.format("CASE-%04d", seq.incrementAndGet());
        CaseFile c = new CaseFile(id, txnId, accountNo, customer, amount, score, outcome, hits, clock.instant());
        c.steps.add(new CaseFile.Step(c.createdAt, "Sistem", "OPENED", "Skor " + score + ", keputusan " + outcome));
        cases.put(id, c);
        return c;
    }

    public CaseFile get(String id) {
        CaseFile c = id == null ? null : cases.get(id);
        if (c == null) {
            throw Problem.notFound("Kasus");
        }
        return c;
    }

    public List<CaseFile> list(CaseFile.Status status) {
        return cases.values().stream().filter(c -> status == null || c.status() == status)
                .sorted(Comparator.comparing(CaseFile::createdAt).reversed().thenComparing(CaseFile::id, Comparator.reverseOrder())).toList();
    }

    public void claim(String id, Actor who) {
        require(who, Actor.Role.ANALYST, "Hanya analis yang bisa mengambil kasus");
        CaseFile c = get(id);
        synchronized (c) {
            if (c.status != CaseFile.Status.OPEN) {
                throw Problem.conflict("WRONG_STATE", "Kasus sudah diambil atau diproses");
            }
            c.status = CaseFile.Status.IN_REVIEW;
            c.assignee = who.id();
            c.steps.add(new CaseFile.Step(clock.instant(), who.name(), "CLAIMED", null));
        }
    }

    public void propose(String id, Actor who, CaseFile.Resolution r, String note) {
        require(who, Actor.Role.ANALYST, "Hanya analis yang bisa mengusulkan keputusan");
        if (r == null) {
            throw Problem.invalid("resolution", "pilih RELEASE, CONFIRM_FRAUD, atau REPORT_LTKM");
        }
        String n = note(note, true);
        CaseFile c = get(id);
        synchronized (c) {
            if (c.status != CaseFile.Status.IN_REVIEW) {
                throw Problem.conflict("WRONG_STATE", "Ambil kasus dulu sebelum mengusulkan keputusan");
            }
            if (!who.id().equals(c.assignee)) {
                throw Problem.forbidden("Kasus ini sedang ditangani analis lain");
            }
            c.status = CaseFile.Status.PENDING_APPROVAL;
            c.proposal = r;
            c.proposedBy = who.id();
            c.steps.add(new CaseFile.Step(clock.instant(), who.name(), "PROPOSED " + r, n));
        }
    }

    /** Returns the approved resolution so the caller can carry it out (release, freeze, report). */
    public CaseFile.Resolution approve(String id, Actor who, String note) {
        require(who, Actor.Role.SUPERVISOR, "Hanya supervisor yang bisa menyetujui");
        String n = note(note, false);
        CaseFile c = get(id);
        synchronized (c) {
            if (c.status != CaseFile.Status.PENDING_APPROVAL) {
                throw Problem.conflict("WRONG_STATE", "Tidak ada usulan yang menunggu persetujuan");
            }
            if (who.id().equals(c.proposedBy)) {
                throw Problem.forbidden("Pengusul tidak boleh menyetujui usulannya sendiri (prinsip 4 mata)");
            }
            c.status = CaseFile.Status.CLOSED;
            c.resolution = c.proposal;
            c.approvedBy = who.id();
            c.steps.add(new CaseFile.Step(clock.instant(), who.name(), "APPROVED " + c.resolution, n));
            return c.resolution;
        }
    }

    public void sendBack(String id, Actor who, String note) {
        require(who, Actor.Role.SUPERVISOR, "Hanya supervisor yang bisa mengembalikan usulan");
        String n = note(note, true);
        CaseFile c = get(id);
        synchronized (c) {
            if (c.status != CaseFile.Status.PENDING_APPROVAL) {
                throw Problem.conflict("WRONG_STATE", "Tidak ada usulan yang menunggu persetujuan");
            }
            c.status = CaseFile.Status.IN_REVIEW;
            c.steps.add(new CaseFile.Step(clock.instant(), who.name(), "SENT_BACK " + c.proposal, n));
            c.proposal = null;
            c.proposedBy = null;
        }
    }

    private static void require(Actor who, Actor.Role role, String why) {
        if (who == null || who.role() != role) {
            throw Problem.forbidden(why);
        }
    }

    private static String note(String note, boolean required) {
        String n = note == null ? "" : note.strip();
        if (required && n.length() < 5) {
            throw Problem.invalid("note", "tulis alasan minimal 5 huruf");
        }
        if (n.length() > MAX_NOTE || n.chars().anyMatch(ch -> Character.isISOControl(ch) && ch != '\n')) {
            throw Problem.invalid("note", "maksimal " + MAX_NOTE + " huruf, tanpa karakter kontrol");
        }
        return n.isEmpty() ? null : n;
    }
}
