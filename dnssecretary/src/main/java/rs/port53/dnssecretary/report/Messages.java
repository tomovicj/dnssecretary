package rs.port53.dnssecretary.report;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;

/** User-facing texts from rs/port53/dnssecretary/messages.properties. */
public final class Messages {

    private static final ResourceBundle BUNDLE = ResourceBundle.getBundle("rs.port53.dnssecretary.messages", Locale.ROOT);

    private Messages() {}

    public static String get(String key, Object... args) {
        String pattern = BUNDLE.getString(key);
        return args.length == 0 ? pattern : new MessageFormat(pattern, Locale.ROOT).format(args);
    }
}
