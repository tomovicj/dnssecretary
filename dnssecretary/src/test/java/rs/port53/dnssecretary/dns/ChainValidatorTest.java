package rs.port53.dnssecretary.dns;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import rs.port53.dnssecretary.collect.ZoneSnapshot;
import rs.port53.dnssecretary.testutil.Snapshots;

class ChainValidatorTest {

    private final ZoneSnapshot s = Snapshots.load(Snapshots.GOOD);

    @Test
    void labChainIsSecure() {
        ChainValidator.Result r = ChainValidator.validate(s.trustAnchor(), s.chain(), s.collectedAt());
        assertThat(r.problems()).isEmpty();
        assertThat(r.secure()).isTrue();
    }

    @Test
    void withoutAnchorItIsNot() {
        assertThat(ChainValidator.validate(List.of(), s.chain(), s.collectedAt()).secure()).isFalse();
    }

    @Test
    void afterTheSignaturesExpireItIsNot() {
        ChainValidator.Result r = ChainValidator.validate(s.trustAnchor(), s.chain(),
                s.collectedAt().plus(Duration.ofDays(60)));
        assertThat(r.secure()).isFalse();
        assertThat(r.problems()).isNotEmpty();
    }
}
