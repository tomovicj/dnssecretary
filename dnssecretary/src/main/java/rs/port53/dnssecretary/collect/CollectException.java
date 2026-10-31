package rs.port53.dnssecretary.collect;

/** Collecting failed so badly that there is nothing to check (exit code 3). The message is shown to the user. */
public class CollectException extends Exception {

    public CollectException(String message) {
        super(message);
    }

    public CollectException(String message, Throwable cause) {
        super(message, cause);
    }
}
