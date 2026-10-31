package rs.port53.dnssecretary.web;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import org.xbill.DNS.Name;
import rs.port53.dnssecretary.collect.ZoneSnapshot;

/**
 * Scans every zone on a fixed delay, and one zone or all on demand (after an execution or a
 * demo action): collect → check → diagnose → plan → script, into the ZoneStore. Collecting holds
 * the lab's read lock, so a scan never sees a half-done execution.
 */
final class Scanner {

    interface Collect {
        ZoneSnapshot collect(Name zone) throws Exception;
    }

    interface Analyze {
        ZoneStore.Scan analyze(ZoneSnapshot snapshot) throws Exception;
    }

    interface Listener {
        void scanned(Name zone, ZoneStore.Update update);
    }

    private final ZoneStore store;
    private final Collect collect;
    private final Analyze analyze;
    private final Lock labRead;
    private final Clock clock;
    private final Listener listener;
    private final ExecutorService workers;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();

    Scanner(ZoneStore store, Collect collect, Analyze analyze, Lock labRead, Clock clock, Listener listener,
            int threads) {
        this.store = store;
        this.collect = collect;
        this.analyze = analyze;
        this.labRead = labRead;
        this.clock = clock;
        this.listener = listener;
        this.workers = Executors.newFixedThreadPool(threads);
    }

    void start(Duration interval) {
        timer.scheduleWithFixedDelay(this::scanAll, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Scans every zone, in parallel; returns when all are done. */
    void scanAll() {
        List<Callable<Void>> tasks = store.zones().stream().map(z -> (Callable<Void>) () -> {
            scan(z);
            return null;
        }).toList();
        try {
            workers.invokeAll(tasks);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void scanAllLater() {
        workers.submit(this::scanAll);
    }

    /** One zone. A zone that can't be collected keeps its last scan (the lab may be restarting). */
    void scan(Name zone) {
        ZoneSnapshot snapshot;
        labRead.lock();
        try {
            snapshot = collect.collect(zone);
        } catch (Exception e) {
            System.err.println("scan " + zone + ": " + e.getMessage());
            return;
        } finally {
            labRead.unlock();
        }
        try {
            ZoneStore.Scan scan = analyze.analyze(snapshot);
            listener.scanned(zone, store.update(zone, scan, clock.instant()));
        } catch (Exception e) {
            System.err.println("scan " + zone + ": " + e);
        }
    }

    void stop() {
        timer.shutdownNow();
        workers.shutdownNow();
    }
}
