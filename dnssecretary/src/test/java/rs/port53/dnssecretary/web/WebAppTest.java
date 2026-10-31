package rs.port53.dnssecretary.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import rs.port53.dnssecretary.catalog.Catalog;
import rs.port53.dnssecretary.collect.ZoneSnapshot;
import rs.port53.dnssecretary.config.AppConfig;
import rs.port53.dnssecretary.config.Thresholds;
import rs.port53.dnssecretary.run.RunResult;
import rs.port53.dnssecretary.testutil.LabConfig;
import rs.port53.dnssecretary.testutil.Snapshots;
import rs.port53.dnssecretary.verify.VerifyResult;

/**
 * The web API over real HTTP, on the recorded snapshots. The sandbox, the lab and demo.sh
 * are fakes: a script that contains "BAD" fails, everything else passes.
 */
class WebAppTest {

    private static final Catalog CATALOG = Catalog.load();
    private static final VerifyResult OK = new VerifyResult("x.", true, List.of(), List.of(), true);

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<String> simulated = Collections.synchronizedList(new ArrayList<>());
    private final List<String> executed = Collections.synchronizedList(new ArrayList<>());
    private final List<List<String>> demos = Collections.synchronizedList(new ArrayList<>());
    private WebApp app;
    private String base;

    @BeforeEach
    void start() {
        Clock clock = Clock.fixed(Snapshots.load(Snapshots.GOOD).collectedAt(), ZoneOffset.UTC);
        AppConfig config = new AppConfig(null, Thresholds.defaults(), null, LabConfig.fix());
        JobService.Runner sandbox = (zone, script, id, log) -> {
            simulated.add(zone + " " + id);
            log.accept("sandbox: running " + id);
            return script.contains("BAD") ? RunResult.of(1, null) : RunResult.of(0, OK);
        };
        JobService.Runner lab = (zone, script, id, log) -> {
            executed.add(zone.toString());
            log.accept("executed");
            return RunResult.of(0, OK);
        };
        app = new WebApp(CATALOG, WebApp.labZones(CATALOG),
                z -> Snapshots.load(z.toString(true)),
                s -> WebApp.analyze(s, config, CATALOG, clock, ZoneSnapshot::localKeys),
                sandbox, lab, (args, log) -> {
                    demos.add(args);
                    return 0;
                }, clock, null, 4);
        app.scanNow();
        base = "http://localhost:" + app.start(0);
    }

    @AfterEach
    void stop() {
        app.stop();
    }

    @Test
    void zonesAreListedBrokenFirst() throws Exception {
        JsonNode rows = get("/api/zones");
        assertThat(rows).hasSize(31);
        assertThat(rows.get(0).path("status").asText()).isEqualTo("CRITICAL");
        assertThat(rows.get(30).path("status").asText()).isEqualTo("OK");

        JsonNode good = row(rows, "good.test.");
        assertThat(good.path("issues")).isEmpty();
        assertThat(good.path("verified").path("state").asText()).isEqualTo("NONE");
        assertThat(good.path("brokenSince").isNull()).isTrue();
        assertThat(good.path("nextExpiry").asText()).startsWith("20");

        JsonNode a1 = row(rows, "a1-expired.test.");
        assertThat(a1.path("issues").get(0).path("id").asText()).isEqualTo("A1");
        assertThat(a1.path("issues").get(0).path("title").asText()).isEqualTo("Expired signature");
    }

    @Test
    void everyBrokenZoneIsSimulatedAutomatically() throws Exception {
        await(() -> verified("a1-expired.test.").equals("PASS"));
        await(() -> simulated.size() == 30);
        assertThat(verified("good.test.")).isEqualTo("NONE");
    }

    @Test
    void zoneDetailHasReportScriptAndJobs() throws Exception {
        await(() -> verified("a1-expired.test.").equals("PASS"));
        JsonNode d = get("/api/zones/a1-expired.test");
        assertThat(d.path("report").path("diagnoses").get(0).path("id").asText()).isEqualTo("A1");
        assertThat(d.path("script").path("generated").asText()).startsWith("#!/bin/sh\n").contains("dnssec-signzone");
        assertThat(d.path("script").path("lastSimulated").asText()).isEqualTo(d.path("script").path("generated").asText());
        assertThat(d.path("jobs").get(0).path("kind").asText()).isEqualTo("SIMULATION");
        assertThat(d.path("jobs").get(0).has("log")).isFalse();
    }

    @Test
    void anEditedScriptIsSimulatedWithItsLog() throws Exception {
        JsonNode job = post("/api/zones/a1-expired.test/simulations", "{\"script\":\"echo BAD\"}", 202);
        assertThat(job.path("edited").asBoolean()).isTrue();
        String id = job.path("id").asText();

        await(() -> state(id).equals("FAIL"));
        JsonNode full = get("/api/jobs/" + id);
        assertThat(full.path("log").get(0).asText()).isEqualTo("sandbox: running " + id);
        await(() -> verified("a1-expired.test.").equals("FAIL"));
    }

    @Test
    void executionRunsOnlyAfterItsSimulationPasses() throws Exception {
        String bad = post("/api/zones/a1-expired.test/executions", "{\"script\":\"echo BAD\"}", 202).path("id").asText();
        await(() -> state(bad).equals("FAIL"));
        assertThat(get("/api/jobs/" + bad).path("error").asText()).contains("simulation did not pass");
        assertThat(executed).isEmpty();

        String ok = post("/api/zones/a1-expired.test/executions", "", 202).path("id").asText();
        await(() -> state(ok).equals("PASS"));
        assertThat(executed).containsExactly("a1-expired.test.");
        assertThat(get("/api/jobs/" + ok).path("log").toString()).contains("simulation passed").contains("executed");
    }

    @Test
    void executionWithoutSimulationRunsRightAway() throws Exception {
        String id = post("/api/zones/b1-ds-stale.test/executions", "{\"simulateFirst\":false}", 202).path("id").asText();
        await(() -> state(id).equals("PASS"));
        assertThat(executed).containsExactly("b1-ds-stale.test.");
        assertThat(get("/api/jobs/" + id).path("log").toString()).doesNotContain("simulating");
    }

    @Test
    void demoBreaksOnlyCatalogueZones() throws Exception {
        String id = post("/api/demo/break", "{\"zone\":\"a1-expired.test\"}", 202).path("id").asText();
        await(() -> state(id).equals("PASS"));
        assertThat(demos).containsExactly(List.of("break", "a1-expired.test"));

        post("/api/demo/break", "{\"zone\":\"good.test\"}", 400);
        assertThat(get("/api/demo/scenarios")).hasSize(30);
    }

    @Test
    void errorsAreJson() throws Exception {
        assertThat(send(HttpRequest.newBuilder(URI.create(base + "/api/zones/nope.test")).GET(), 404)
                .path("error").asText()).contains("Unknown zone");
        assertThat(post("/api/zones/good.test/simulations", "", 400).path("error").asText())
                .contains("nothing to fix");
        post("/api/zones/a1-expired.test/simulations", "{not json", 400);
    }

    @Test
    void eventsStreamJobsAndZones() throws Exception {
        CompletableFuture<HttpResponse<Stream<String>>> sse = http.sendAsync(
                HttpRequest.newBuilder(URI.create(base + "/api/events")).header("Accept", "text/event-stream").GET().build(),
                HttpResponse.BodyHandlers.ofLines());
        Thread.sleep(300);   // the stream is open
        post("/api/zones/a1-expired.test/simulations", "", 202);

        List<String> events = sse.get().body()
                .filter(l -> l.startsWith("event:"))
                .limit(3)
                .toList();
        assertThat(events).contains("event: job", "event: zone");
    }

    // --- helpers

    private String verified(String zone) {
        try {
            return row(get("/api/zones"), zone).path("verified").path("state").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String state(String jobId) {
        try {
            return get("/api/jobs/" + jobId).path("state").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode row(JsonNode rows, String zone) {
        for (JsonNode r : rows) {
            if (r.path("zone").asText().equals(zone)) {
                return r;
            }
        }
        throw new AssertionError("no row " + zone);
    }

    private JsonNode get(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path)).GET(), 200);
    }

    private JsonNode post(String path, String body, int status) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)), status);
    }

    private JsonNode send(HttpRequest.Builder req, int status) throws Exception {
        HttpResponse<String> r = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        return Api.JSON.readTree(r.body());
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("condition not met within 10 s");
            }
            Thread.sleep(50);
        }
    }
}
