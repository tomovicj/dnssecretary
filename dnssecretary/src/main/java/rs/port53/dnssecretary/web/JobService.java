package rs.port53.dnssecretary.web;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.function.Consumer;
import org.xbill.DNS.Name;
import rs.port53.dnssecretary.run.RunResult;

/**
 * Runs jobs in the background and reports them over SSE.
 * Simulations (sandbox) run up to 2 at a time and only read the lab (read lock: the sandbox
 * copies it). Executions and demo actions change the lab, one at a time (write lock), and are
 * followed by a scan, so the dashboard shows the result right away.
 */
final class JobService {

    /** Same shape as SandboxRunner.run and ProdRunner.run. */
    interface Runner {
        RunResult run(Name zone, String script, String jobId, Consumer<String> log);
    }

    /** scripts/demo.sh with these arguments; returns its exit code. */
    interface Demo {
        int run(List<String> args, Consumer<String> log) throws Exception;
    }

    private final ZoneStore store;
    private final Events events;
    private final Runner simulator;
    private final Runner executor;
    private final Demo demo;
    private final ReadWriteLock lab;
    private final Clock clock;
    private final Consumer<Name> rescan;
    private final Runnable rescanAll;
    private final ExecutorService simulations = Executors.newFixedThreadPool(2);
    private final ExecutorService changes = Executors.newSingleThreadExecutor();
    private final AtomicInteger ids = new AtomicInteger();

    JobService(ZoneStore store, Events events, Runner simulator, Runner executor, Demo demo, ReadWriteLock lab,
               Clock clock, Consumer<Name> rescan, Runnable rescanAll) {
        this.store = store;
        this.events = events;
        this.simulator = simulator;
        this.executor = executor;
        this.demo = demo;
        this.lab = lab;
        this.clock = clock;
        this.rescan = rescan;
        this.rescanAll = rescanAll;
    }

    /** Try a script in the sandbox; its outcome becomes the zone's "verified". */
    Job simulate(Name zone, String script) {
        Job job = newJob("sim", Job.Kind.SIMULATION, zone, script);
        store.simulationQueued(zone, job.id);
        events.publish("zone", store.row(zone));
        simulations.submit(() -> {
            begin(job);
            RunResult r = sandbox(job, job.id, line -> log(job, line));
            end(job, r);
        });
        return job;
    }

    /** Run a script on the lab; with simulateFirst only if a simulation of exactly this script passes. */
    Job execute(Name zone, String script, boolean simulateFirst) {
        Job job = newJob("exec", Job.Kind.EXECUTION, zone, script);
        changes.submit(() -> {
            begin(job);
            if (simulateFirst) {
                log(job, "simulating this script in the sandbox first");
                RunResult sim = sandbox(job, job.id + "-sim", line -> log(job, "[sandbox] " + line));
                if (sim.outcome() != RunResult.Outcome.PASS) {
                    end(job, new RunResult(RunResult.Outcome.FAIL, sim.scriptExit(), sim.verify(),
                            "the simulation did not pass, nothing was executed"));
                    return;
                }
                log(job, "simulation passed; executing on the lab");
            }
            RunResult r;
            lab.writeLock().lock();
            try {
                r = executor.run(zone, script, job.id, line -> log(job, line));
            } finally {
                lab.writeLock().unlock();
            }
            end(job, r);
            rescan.accept(zone);
        });
        return job;
    }

    /** scripts/demo.sh heal | reset | break &lt;zone&gt;, then a scan of every zone. */
    Job demo(List<String> args, Name zone) {
        Job job = newJob("demo", Job.Kind.DEMO, zone, "sh scripts/demo.sh " + String.join(" ", args));
        changes.submit(() -> {
            begin(job);
            RunResult r;
            lab.writeLock().lock();
            try {
                int rc = demo.run(args, line -> log(job, line));
                r = rc == 0 ? new RunResult(RunResult.Outcome.PASS, 0, null, null)
                        : new RunResult(RunResult.Outcome.FAIL, rc, null, "demo.sh exit " + rc);
            } catch (Exception e) {
                r = RunResult.error(e.getMessage());
            } finally {
                lab.writeLock().unlock();
            }
            end(job, r);
            rescanAll.run();
        });
        return job;
    }

    void stop() {
        simulations.shutdownNow();
        changes.shutdownNow();
    }

    /** A sandbox run under the read lock; it updates the zone's "verified". */
    private RunResult sandbox(Job job, String jobId, Consumer<String> log) {
        RunResult r;
        lab.readLock().lock();
        try {
            r = simulator.run(job.zone, job.script, jobId, log);
        } finally {
            lab.readLock().unlock();
        }
        Job result = new Job(jobId, Job.Kind.SIMULATION, job.zone, job.script, job.edited);
        result.finish(r, clock.instant());
        store.simulationDone(job.zone, job.script, result, clock.instant());
        events.publish("zone", store.row(job.zone));
        return r;
    }

    private Job newJob(String prefix, Job.Kind kind, Name zone, String script) {
        boolean edited = zone != null && kind != Job.Kind.DEMO && !script.equals(store.generated(zone));
        Job job = new Job(prefix + "-" + ids.incrementAndGet(), kind, zone, script, edited);
        store.add(job);
        events.publish("job", job.view(false));
        return job;
    }

    private void begin(Job job) {
        job.start(clock.instant());
        events.publish("job", job.view(false));
    }

    private void end(Job job, RunResult r) {
        job.finish(r, clock.instant());
        events.publish("job", job.view(false));
    }

    private void log(Job job, String line) {
        job.log(line);
        events.publish("log", new Api.LogLine(job.id, line));
    }
}
