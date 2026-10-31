package rs.port53.dnssecretary.dns;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * IP address literals only. InetAddress.getByName with a host name would ask the system
 * resolver (in the container: Docker DNS, which strips EDE), and the tool never does that.
 */
public final class Addresses {

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private Addresses() {}

    public static InetAddress parse(String literal) {
        String s = literal.strip();
        if (!IPV4.matcher(s).matches() && !s.contains(":")) {
            throw new IllegalArgumentException("Not an IP address: " + literal);
        }
        try {
            return InetAddress.getByName(s);   // a literal: no DNS lookup
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Not an IP address: " + literal, e);
        }
    }
}
