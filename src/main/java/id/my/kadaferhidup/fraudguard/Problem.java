package id.my.kadaferhidup.fraudguard;

/** A refusal with an HTTP status and a stable code the console can react to. */
public final class Problem extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;

    public Problem(int status, String code, String message) {
        super(message, null, false, false);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static Problem invalid(String field, String why) {
        return new Problem(422, "INVALID_FIELD", field + ": " + why);
    }

    public static Problem notFound(String what) {
        return new Problem(404, "NOT_FOUND", what + " tidak ditemukan");
    }

    public static Problem forbidden(String why) {
        return new Problem(403, "FORBIDDEN", why);
    }

    public static Problem conflict(String code, String why) {
        return new Problem(409, code, why);
    }
}
