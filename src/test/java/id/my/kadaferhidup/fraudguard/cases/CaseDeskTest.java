package id.my.kadaferhidup.fraudguard.cases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import id.my.kadaferhidup.fraudguard.Problem;
import id.my.kadaferhidup.fraudguard.risk.Outcome;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CaseDeskTest {

    final CaseDesk desk = new CaseDesk(Clock.systemUTC());
    final Actor ana = new Actor("a1", "Ahmad Umraithi (Analis)", Actor.Role.ANALYST);
    final Actor ana2 = new Actor("a2", "Ahmad Umraithi (Analis 2)", Actor.Role.ANALYST);
    final Actor sup = new Actor("s1", "Ahmad Umraithi (Supervisor)", Actor.Role.SUPERVISOR);

    CaseFile open() {
        return desk.open("t1", "7001", "Ahmad Umraithi", 1000, 50, Outcome.REVIEW, List.of());
    }

    static int status(Runnable r) {
        return assertThrows(Problem.class, r::run).status();
    }

    @Test
    void fullPathEndsWithTheApprovedResolution() {
        CaseFile c = open();
        desk.claim(c.id(), ana);
        desk.propose(c.id(), ana, CaseFile.Resolution.CONFIRM_FRAUD, "Pola rekening penampung");
        assertEquals(CaseFile.Resolution.CONFIRM_FRAUD, desk.approve(c.id(), sup, null));
        assertEquals(CaseFile.Status.CLOSED, c.status());
        assertEquals("s1", c.approvedBy());
        assertEquals(4, c.steps().size());
    }

    @Test
    void rolesCannotStepIntoEachOthersJob() {
        CaseFile c = open();
        assertEquals(403, status(() -> desk.claim(c.id(), sup)), "supervisor tidak mengambil kasus");
        desk.claim(c.id(), ana);
        assertEquals(403, status(() -> desk.propose(c.id(), ana2, CaseFile.Resolution.RELEASE, "bukan kasusku")), "analis lain");
        assertEquals(403, status(() -> desk.propose(c.id(), sup, CaseFile.Resolution.RELEASE, "supervisor")));
        desk.propose(c.id(), ana, CaseFile.Resolution.RELEASE, "Transaksi wajar");
        assertEquals(403, status(() -> desk.approve(c.id(), ana, null)), "analis tidak menyetujui");
    }

    @Test
    void nobodyApprovesTheirOwnProposal() {
        Actor both = new Actor("x", "Ahmad Umraithi", Actor.Role.SUPERVISOR);
        CaseFile c = open();
        desk.claim(c.id(), ana);
        desk.propose(c.id(), ana, CaseFile.Resolution.RELEASE, "Transaksi wajar");
        // the proposer id is what is compared, so a supervisor token with the analyst's id would also be refused
        Actor sameIdentity = new Actor("a1", "Ahmad Umraithi", Actor.Role.SUPERVISOR);
        assertEquals(403, status(() -> desk.approve(c.id(), sameIdentity, null)));
        assertEquals(CaseFile.Resolution.RELEASE, desk.approve(c.id(), both, null));
    }

    @Test
    void stepsOutOfOrderAreRefused() {
        CaseFile c = open();
        assertEquals(409, status(() -> desk.propose(c.id(), ana, CaseFile.Resolution.RELEASE, "belum diambil")));
        assertEquals(409, status(() -> desk.approve(c.id(), sup, null)));
        desk.claim(c.id(), ana);
        assertEquals(409, status(() -> desk.claim(c.id(), ana2)));
        assertEquals(422, status(() -> desk.propose(c.id(), ana, CaseFile.Resolution.RELEASE, "ok")), "alasan terlalu pendek");
        desk.propose(c.id(), ana, CaseFile.Resolution.RELEASE, "Transaksi wajar");
        desk.sendBack(c.id(), sup, "Cek dulu riwayat perangkat");
        assertEquals(CaseFile.Status.IN_REVIEW, c.status());
        desk.propose(c.id(), ana, CaseFile.Resolution.REPORT_LTKM, "Perangkat dipakai 4 rekening");
        desk.approve(c.id(), sup, null);
        assertEquals(409, status(() -> desk.approve(c.id(), sup, null)), "tidak bisa disetujui dua kali");
    }

    @Test
    void twoAnalystsClaimingAtOnceOnlyOneWins() throws Exception {
        CaseFile c = open();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger won = new AtomicInteger();
        for (int i = 0; i < 8; i++) {
            Actor a = new Actor("a" + i, "Ahmad Umraithi", Actor.Role.ANALYST);
            pool.submit(() -> {
                go.await();
                try {
                    desk.claim(c.id(), a);
                    won.incrementAndGet();
                } catch (Problem p) {
                    // expected for all but one
                }
                return null;
            });
        }
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(1, won.get());
    }
}
