package rs.port53.dnssecretary.collect;

import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import rs.port53.dnssecretary.config.AppConfig;

/** Where the whole zone comes from (source Z). Without it, checks see only the sampled names. */
public interface ZoneSource {

    /**
     * @param servers  the zone's name server addresses (for AXFR)
     * @param warnings add a Serbian message here when the zone can't be loaded
     */
    Optional<List<Record>> load(Name zone, List<InetAddress> servers, List<String> warnings);

    static ZoneSource none() {
        return (zone, servers, warnings) -> Optional.empty();
    }

    static ZoneSource from(AppConfig.Collect c, Duration timeout) {
        return switch (c.zoneSource()) {
            case "file" -> new FileZoneSource(c.zoneFileDirs().stream().map(Path::of).toList());
            case "axfr" -> new AxfrZoneSource(timeout);
            case "none" -> none();
            default -> throw new IllegalArgumentException(
                    "collect.zoneSource must be file, axfr or none, not " + c.zoneSource());
        };
    }
}
