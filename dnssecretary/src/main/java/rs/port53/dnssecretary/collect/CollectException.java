package rs.port53.dnssecretary.collect;

/** Collecting failed so badly that there is nothing to check (exit code 3). Message in Serbian. */
public class CollectException extends Exception {

    public CollectException(String message) {
        super(message);
    }

    public CollectException(String message, Throwable cause) {
        super(message, cause);
    }
}
