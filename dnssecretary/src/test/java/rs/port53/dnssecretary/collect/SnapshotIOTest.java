package rs.port53.dnssecretary.collect;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import rs.port53.dnssecretary.testutil.Snapshots;

class SnapshotIOTest {

    static List<String> zones() {
        return Snapshots.allZones();
    }

    /** Reading and writing again gives the same JSON: nothing is lost on the way. */
    @ParameterizedTest
    @MethodSource("zones")
    void roundTripIsLossless(String zone) throws Exception {
        ObjectMapper plain = new ObjectMapper();
        JsonNode original;
        try (InputStream in = getClass().getResourceAsStream("/snapshots/" + zone + ".json")) {
            original = plain.readTree(in);
        }
        ZoneSnapshot s = Snapshots.load(zone);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SnapshotIO.write(s, out);
        assertThat(plain.readTree(out.toByteArray())).isEqualTo(original);

        ZoneSnapshot again = SnapshotIO.read(new ByteArrayInputStream(out.toByteArray()));
        assertThat(again).isEqualTo(s);
    }
}
