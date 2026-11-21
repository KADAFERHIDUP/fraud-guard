package id.my.kadaferhidup.fraudguard.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import org.junit.jupiter.api.Test;

class AuditLogTest {

    @Test
    void editingAnyPastEntryIsDetected() {
        AuditLog log = new AuditLog(Clock.systemUTC());
        log.append("Sistem", "SCORED", "t1", "skor 10");
        log.append("Ahmad Umraithi (Analis)", "PROPOSE", "CASE-0001", "RELEASE");
        log.append("Ahmad Umraithi (Supervisor)", "APPROVE", "CASE-0001", "RELEASE");
        assertTrue(log.verify().intact());
        assertEquals(3, log.verify().entries());

        log.tamper(1, "CONFIRM_FRAUD");
        AuditLog.Verification v = log.verify();
        assertFalse(v.intact());
        assertEquals(Long.valueOf(2), v.brokenAt());
    }

    @Test
    void eachEntryPointsAtThePreviousOne() {
        AuditLog log = new AuditLog(Clock.systemUTC());
        AuditLog.Entry a = log.append("x", "A", "s", null);
        AuditLog.Entry b = log.append("x", "B", "s", null);
        assertEquals(AuditLog.GENESIS, a.prevHash());
        assertEquals(a.hash(), b.prevHash());
    }
}
