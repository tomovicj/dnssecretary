package rs.port53.dnssecretary.check;

import java.time.Clock;
import java.time.Instant;
import rs.port53.dnssecretary.config.Thresholds;

/**
 * What a check may use besides the snapshot. The key directory (source K) is in the
 * snapshot itself (ZoneSnapshot.localKeys), so checks stay pure.
 *
 * @param clock in tests Clock.fixed(snapshot.collectedAt()); in the CLI the system clock or --now
 */
public record CheckContext(Clock clock, Thresholds thresholds) {

    public Instant now() {
        return clock.instant();
    }
}
