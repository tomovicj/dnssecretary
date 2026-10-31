package rs.port53.dnssecretary.fix;

/** A fix step failed (signing, dnssec-verify, a parent command). Nothing after that step is done. */
public final class FixException extends Exception {

    public FixException(String message) {
        super(message);
    }

    public FixException(String message, Throwable cause) {
        super(message, cause);
    }
}
