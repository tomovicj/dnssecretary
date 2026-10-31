package rs.port53.dnssecretary.collect;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.ZoneTransferException;
import org.xbill.DNS.ZoneTransferIn;

/**
 * AXFR from the zone's name servers, first one that allows it.
 * In the lab NSD doesn't allow transfers yet (docs/predlog-java.md 2.5, point 8).
 */
public final class AxfrZoneSource implements ZoneSource {

    private final Duration timeout;

    public AxfrZoneSource(Duration timeout) {
        this.timeout = timeout;
    }

    @Override
    public Optional<List<Record>> load(Name zone, List<InetAddress> servers, List<String> warnings) {
        List<String> errors = new ArrayList<>();
        for (InetAddress server : servers) {
            try {
                ZoneTransferIn xfr = ZoneTransferIn.newAXFR(zone, new InetSocketAddress(server, 53), null);
                xfr.setTimeout(timeout);
                xfr.run();
                return Optional.of(List.copyOf(xfr.getAXFR()));
            } catch (IOException | ZoneTransferException e) {
                errors.add(server.getHostAddress() + ": " + e.getMessage());
            }
        }
        warnings.add("AXFR nije uspeo (" + String.join("; ", errors) + ") – provere rade samo nad uzorcima.");
        return Optional.empty();
    }
}
