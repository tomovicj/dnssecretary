package rs.port53.dnssecretary.model;

/** How bad a finding is. Ordered: the larger one wins when findings are merged. */
public enum Severity {
    /** Validation works, but the setup is unsafe or bad practice ("risk" in the README). */
    RISK,
    /** Validation fails: validating resolvers return SERVFAIL (for some or all users). */
    CRITICAL;

    public static Severity max(Severity a, Severity b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
