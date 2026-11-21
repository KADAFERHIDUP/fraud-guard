package id.my.kadaferhidup.fraudguard.risk;

/** One rule that fired: its code, the points it adds, and a sentence an analyst can read. */
public record Hit(String rule, int points, String detail, boolean hardBlock) {

    public static Hit of(String rule, int points, String detail) {
        return new Hit(rule, points, detail, false);
    }

    public static Hit block(String rule, String detail) {
        return new Hit(rule, 100, detail, true);
    }
}
