package rs.port53.dnssecretary.web;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JavalinJackson;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.stream.Stream;
import org.xbill.DNS.Name;
import org.xbill.DNS.RRSIGRecord;
import org.xbill.DNS.TextParseException;
import rs.port53.dnssecretary.catalog.Catalog;
import rs.port53.dnssecretary.catalog.ErrorId;
import rs.port53.dnssecretary.check.CheckContext;
import rs.port53.dnssecretary.collect.Collector;
import rs.port53.dnssecretary.collect.KeyDirectory;
import rs.port53.dnssecretary.collect.LocalKey;
import rs.port53.dnssecretary.collect.NameserverView;
import rs.port53.dnssecretary.collect.SignedRRset;
import rs.port53.dnssecretary.collect.ZoneSnapshot;
import rs.port53.dnssecretary.config.AppConfig;
import rs.port53.dnssecretary.diagnose.Analysis;
import rs.port53.dnssecretary.fix.FixPlan;
import rs.port53.dnssecretary.fix.FixPlanner;
import rs.port53.dnssecretary.fix.ScriptGenerator;
import rs.port53.dnssecretary.report.Report;
import rs.port53.dnssecretary.run.ProdRunner;
import rs.port53.dnssecretary.run.SandboxRunner;
import rs.port53.dnssecretary.verify.LiveVerifier;

/**
 * The web app (dnssecretary serve): REST + SSE for the dashboard, and webapp/dist if it is built.
 * API contract: the "API ugovor" section of the pivot plan. No database, no auth: a demo.
 */
public final class WebApp {

    private final Catalog catalog;
    private final ZoneStore store;
    private final Events events = new Events();
    private final Scanner scanner;
    private final JobService jobs;
    private final Path staticDir;
    private Javalin server;

    WebApp(Catalog catalog, List<Name> zones, Scanner.Collect collect, Scanner.Analyze analyze,
           JobService.Runner simulator, JobService.Runner executor, JobService.Demo demo, Clock clock,
           Path staticDir, int scanThreads) {
        this.catalog = catalog;
        this.staticDir = staticDir;
        this.store = new ZoneStore(zones, catalog);
        ReentrantReadWriteLock lab = new ReentrantReadWriteLock(true);
        Scanner[] scan = new Scanner[1];
        this.jobs = new JobService(store, events, simulator, executor, demo, lab, clock,
                z -> scan[0].scan(z), () -> scan[0].scanAll());
        this.scanner = new Scanner(store, collect, analyze, lab.readLock(), clock, this::scanned, scanThreads);
        scan[0] = scanner;
    }

    /** The real thing: the lab's servers, the sandbox, the lab's scripts. */
    public static WebApp forLab(AppConfig config, Catalog catalog, Path labDir, List<Name> zones, Clock clock) {
        AppConfig.Fix fix = Objects.requireNonNull(config.fix(), "the configuration has no fix section");
        Collector collector = new Collector(config, clock);
        Scanner.Collect collect = z -> collector.collect(z, probes(catalog, z));
        Function<ZoneSnapshot, List<LocalKey>> keys = s -> fix.keyDir() == null ? List.of()
                : KeyDirectory.load(Path.of(fix.keyDir()), s.zone(), new ArrayList<>());
        LiveVerifier verifier = new LiveVerifier(config, catalog, clock);
        ProdRunner prod = ProdRunner.forLab(labDir, Path.of(fix.workDir()), z -> verifier.verify(z).result());
        return new WebApp(catalog, zones, collect, s -> analyze(s, config, catalog, clock, keys),
                SandboxRunner.forLab(labDir)::run, prod::run, (args, log) -> demo(labDir, args, log), clock,
                labDir.resolve("webapp/dist"), 4);
    }

    /**
     * The errors scripts/demo.sh can put on any hosting zone (break.sh "zone" mode, E1 by a live
     * rollover): what the demo panel offers.
     */
    static final List<ErrorId> HOSTING_ERRORS = Stream.of("A1", "A2", "A3", "A4", "A6", "B2", "B3", "B4", "B5",
            "B6", "B7", "D1", "D3", "D4", "D5", "D6", "E1", "E4").map(ErrorId::valueOf).toList();

    /** The demo hosting's customer zones: zones/hosting.list in the lab ("&lt;zone&gt; [&lt;start error&gt;]" lines). */
    public static List<Name> hostingZones(Path labDir) throws IOException {
        try (Stream<String> lines = Files.lines(labDir.resolve("zones/hosting.list"))) {
            return lines.map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .map(l -> name(l.split("\\s+")[0])).toList();
        }
    }

    /** good.test and the lab zone of every catalogue entry. */
    public static List<Name> labZones(Catalog catalog) {
        List<Name> out = new ArrayList<>();
        out.add(name("good.test"));
        catalog.entries().forEach(e -> out.add(Catalog.labZone(e)));
        return out;
    }

    /** Starts the HTTP server; returns its port (0 = any free port). */
    public int start(int port) {
        server = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            cfg.jsonMapper(new JavalinJackson(Api.JSON, false));
            if (staticDir != null && Files.isRegularFile(staticDir.resolve("index.html"))) {
                cfg.staticFiles.add(sf -> {
                    sf.hostedPath = "/";
                    sf.directory = staticDir.toString();
                    sf.location = Location.EXTERNAL;
                });
                cfg.spaRoot.addFile("/", staticDir.resolve("index.html").toString(), Location.EXTERNAL);
            }
        });
        routes(server);
        server.start(port);
        return server.port();
    }

    public void scanEvery(Duration interval) {
        scanner.start(interval);
    }

    /** One scan of every zone, now (tests, and the first scan before serving). */
    public void scanNow() {
        scanner.scanAll();
    }

    public void stop() {
        scanner.stop();
        jobs.stop();
        if (server != null) {
            server.stop();
        }
    }

    private void routes(Javalin app) {
        app.get("/api/zones", ctx -> ctx.json(store.rows()));
        app.get("/api/zones/{zone}", ctx -> ctx.json(store.detail(zone(ctx))));
        app.post("/api/zones/{zone}/simulations", ctx -> {
            Name z = zone(ctx);
            Api.ScriptRequest req = body(ctx, Api.ScriptRequest.class);
            ctx.status(202).json(jobs.simulate(z, script(z, req)).view(false));
        });
        app.post("/api/zones/{zone}/executions", ctx -> {
            Name z = zone(ctx);
            Api.ScriptRequest req = body(ctx, Api.ScriptRequest.class);
            boolean simulateFirst = req == null || req.simulateFirst() == null || req.simulateFirst();
            ctx.status(202).json(jobs.execute(z, script(z, req), simulateFirst).view(false));
        });
        app.get("/api/catalog", ctx -> ctx.json(catalog.entries()));
        app.get("/api/jobs", ctx -> ctx.json(store.jobs()));
        app.get("/api/jobs/{id}", ctx -> ctx.json(store.job(ctx.pathParam("id")).view(true)));
        app.post("/api/scan", ctx -> {
            scanner.scanAllLater();
            ctx.status(202).json(Map.of("scan", "started"));
        });
        app.sse("/api/events", events::add);

        app.get("/api/demo/scenarios", ctx -> ctx.json(HOSTING_ERRORS.stream()
                .map(id -> Map.of("id", id.name(), "title", catalog.get(id).title()))
                .toList()));
        // With an error: a watched zone that is not a catalogue zone (demo.sh checks it is in the
        // hosting list). Without one: a catalogue zone, broken as the catalogue breaks it.
        app.post("/api/demo/break", ctx -> {
            Api.BreakRequest req = body(ctx, Api.BreakRequest.class);
            Name z = name(req == null || req.zone() == null ? "" : req.zone());
            if (req.error() == null) {
                if (catalog.forLabZone(z).isEmpty()) {
                    throw new IllegalArgumentException("Not a catalogue zone: " + z);
                }
                ctx.status(202).json(jobs.demo(List.of("break", z.toString(true)), z).view(false));
                return;
            }
            ErrorId error = HOSTING_ERRORS.stream().filter(id -> id.name().equalsIgnoreCase(req.error()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Not a demo error: " + req.error()));
            if (catalog.forLabZone(z).isPresent() || !store.zones().contains(z)) {
                throw new IllegalArgumentException("Not a hosting zone: " + z);
            }
            ctx.status(202).json(jobs.demo(List.of("break", z.toString(true), error.name()), z).view(false));
        });
        app.post("/api/demo/heal", ctx -> ctx.status(202).json(jobs.demo(List.of("heal"), null).view(false)));
        app.post("/api/demo/reset", ctx -> ctx.status(202).json(jobs.demo(List.of("reset"), null).view(false)));

        app.exception(NoSuchElementException.class, (e, ctx) -> ctx.status(404).json(new Api.Error(e.getMessage())));
        app.exception(IllegalArgumentException.class, (e, ctx) -> ctx.status(400).json(new Api.Error(e.getMessage())));
    }

    /** Every scan: an SSE event when the row changed, and a simulation when there is a new script. */
    private void scanned(Name zone, ZoneStore.Update u) {
        if (u.simulate()) {
            jobs.simulate(zone, store.generated(zone));   // publishes the row (verified: RUNNING)
        } else if (u.rowChanged()) {
            events.publish("zone", store.row(zone));
        }
    }

    private Name zone(Context ctx) {
        Name z = name(ctx.pathParam("zone"));
        if (!store.knows(z)) {
            throw new NoSuchElementException("Unknown zone " + z);
        }
        return z;
    }

    /** The script from the request, or the generated one; there must be something to run. */
    private String script(Name zone, Api.ScriptRequest req) {
        String s = req == null || req.script() == null ? store.generated(zone) : req.script();
        if (s.isBlank()) {
            throw new IllegalArgumentException("No script: " + zone + " has nothing to fix automatically");
        }
        return s;
    }

    private static <T> T body(Context ctx, Class<T> cls) {
        if (ctx.body().isBlank()) {
            return null;
        }
        try {
            return Api.JSON.readValue(ctx.body(), cls);
        } catch (IOException e) {
            throw new IllegalArgumentException("Bad request body: " + e.getMessage());
        }
    }

    static Name name(String s) {
        try {
            return Name.fromString(s.endsWith(".") ? s : s + ".", Name.root);
        } catch (TextParseException e) {
            throw new IllegalArgumentException("Bad zone name: " + s);
        }
    }

    static List<String> probes(Catalog catalog, Name zone) {
        return catalog.forLabZone(zone).map(e -> List.of(e.lab().probe())).orElse(List.of());
    }

    /** check → diagnose → plan → script for one snapshot (what the dashboard and the zone screen show). */
    static ZoneStore.Scan analyze(ZoneSnapshot s, AppConfig config, Catalog catalog, Clock clock,
                                  Function<ZoneSnapshot, List<LocalKey>> keys) throws IOException {
        Report report = Analysis.run(s, new CheckContext(clock, config.thresholds()), catalog);
        FixPlan plan = new FixPlanner(catalog, config.fix()).plan(s, report.diagnoses(), keys.apply(s));
        String script = new ScriptGenerator(config.fix(), catalog).generate(plan);
        return new ZoneStore.Scan(report, plan, script, nextExpiry(s));
    }

    /** The earliest RRSIG expiration: from the zone file if there is one, else from the apex answers. */
    static Instant nextExpiry(ZoneSnapshot s) {
        Stream<SignedRRset> sets = s.fullZone().isPresent() ? s.zoneRRsets().stream()
                : s.nameservers().stream().flatMap(ns -> apex(ns).stream());
        return sets.flatMap(r -> r.sigs().stream()).map(RRSIGRecord::getExpire).min(Comparator.naturalOrder())
                .orElse(null);
    }

    private static List<SignedRRset> apex(NameserverView ns) {
        return List.of(ns.soa().rrset(), ns.dnskey().rrset());
    }

    /** scripts/demo.sh, its output line by line. */
    private static int demo(Path labDir, List<String> args, java.util.function.Consumer<String> log)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("sh", "scripts/demo.sh"));
        cmd.addAll(args);
        Process p = new ProcessBuilder(cmd).directory(labDir.toFile()).redirectErrorStream(true).start();
        p.getOutputStream().close();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            r.lines().forEach(log);
        }
        return p.waitFor();
    }
}
