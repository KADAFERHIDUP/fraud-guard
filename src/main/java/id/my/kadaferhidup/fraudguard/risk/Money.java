package id.my.kadaferhidup.fraudguard.risk;

import java.util.regex.Pattern;

/** Rupiah as strings with exactly two decimals on the wire, sen as {@code long} inside. Never floating point. */
public final class Money {

    private static final Pattern VALUE = Pattern.compile("^(0|[1-9][0-9]{0,12})\\.[0-9]{2}$");
    public static final long RUPIAH = 100;
    public static final long JUTA = 1_000_000 * RUPIAH;

    private Money() {
    }

    /** "150000.00" → 15000000. Returns -1 when the text is not a valid amount. */
    public static long parse(Object value) {
        if (!(value instanceof String s) || !VALUE.matcher(s).matches()) {
            return -1;
        }
        int dot = s.indexOf('.');
        return Long.parseLong(s.substring(0, dot)) * 100 + Long.parseLong(s.substring(dot + 1));
    }

    public static String format(long sen) {
        return String.format("%d.%02d", sen / 100, sen % 100);
    }

    /** For sentences: Rp12.500.000 */
    public static String rupiah(long sen) {
        return "Rp" + String.format("%,d", sen / 100).replace(',', '.');
    }
}
