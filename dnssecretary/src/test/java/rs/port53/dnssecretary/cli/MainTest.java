package rs.port53.dnssecretary.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MainTest {

    @Test
    void catalogWorksWithoutConfig() {
        assertThat(Main.run("catalog")).isEqualTo(0);
        assertThat(Main.run("catalog", "b6")).isEqualTo(0);
        assertThat(Main.run("catalog", "X9")).isEqualTo(Main.EXIT_TOOL_ERROR);
    }

    @Test
    void checkFromSnapshotOfGoodZoneIsOk() throws Exception {
        Path p = Path.of(getClass().getResource("/snapshots/good.test.json").toURI());
        assertThat(Main.run("check", "--from", p.toString())).isEqualTo(0);
        assertThat(Main.run("check", "--json", "--from", p.toString())).isEqualTo(0);
    }

    @Test
    void networkWithoutConfigIsAToolError() {
        assertThat(Main.run("check", "good.test")).isEqualTo(Main.EXIT_TOOL_ERROR);
    }

    @Test
    void fixIsNotThereYet() {
        assertThat(Main.run("fix", "a1-expired.test")).isEqualTo(Main.EXIT_TOOL_ERROR);
    }
}
