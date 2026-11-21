package id.my.kadaferhidup.fraudguard.risk;

import static id.my.kadaferhidup.fraudguard.risk.Money.JUTA;

import java.time.Duration;

/** Every number the rules use, in one place so a bank can tune them without touching rule code. */
public record Thresholds(
        long cashReport,          // LTKT: cash at or above this is reported to PPATK
        long largeAbsolute,
        int largeVsAverage,
        long largeVsAverageMin,
        Duration velocityWindow,
        int velocityWarn,
        int velocityHigh,
        Duration structuringWindow,
        int structuringMinDeposits,
        Duration newAccountAge,
        long newAccountMin,
        long newCounterpartyMin,
        long oddHourMin,
        Duration passThroughWindow,
        long passThroughMinIncoming,
        long newDeviceMin,
        int incomeMultiple,
        long highRiskMin,
        int reviewAt,
        int blockAt) {

    public static Thresholds defaults() {
        return new Thresholds(500 * JUTA, 100 * JUTA, 5, 5 * JUTA, Duration.ofMinutes(10), 5, 10, Duration.ofHours(24), 3,
                Duration.ofDays(7), 10 * JUTA, 25 * JUTA, 10 * JUTA, Duration.ofHours(1), 10 * JUTA, 5 * JUTA, 3, 10 * JUTA, 40, 70);
    }
}
