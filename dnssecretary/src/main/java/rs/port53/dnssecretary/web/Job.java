package rs.port53.dnssecretary.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.xbill.DNS.Name;
import rs.port53.dnssecretary.run.RunResult;
import rs.port53.dnssecretary.verify.VerifyResult;

/** One simulation, execution or demo action: its state and its log, in memory only. */
final class Job {

    enum Kind { SIMULATION, EXECUTION, DEMO }

    enum State { QUEUED, RUNNING, PASS, FAIL, ERROR }

    final String id;
    final Kind kind;
    final Name zone;          // null for demo actions that are not about one zone
    final String script;
    final boolean edited;

    private final List<String> log = new ArrayList<>();
    private State state = State.QUEUED;
    private Instant startedAt;
    private Instant finishedAt;
    private VerifyResult verify;
    private String error;

    Job(String id, Kind kind, Name zone, String script, boolean edited) {
        this.id = id;
        this.kind = kind;
        this.zone = zone;
        this.script = script;
        this.edited = edited;
    }

    synchronized void start(Instant now) {
        state = State.RUNNING;
        startedAt = now;
    }

    synchronized void log(String line) {
        log.add(line);
    }

    synchronized void finish(RunResult r, Instant now) {
        state = switch (r.outcome()) {
            case PASS -> State.PASS;
            case FAIL -> State.FAIL;
            case ERROR -> State.ERROR;
        };
        verify = r.verify();
        error = r.error();
        finishedAt = now;
    }

    synchronized State state() {
        return state;
    }

    synchronized boolean finished() {
        return finishedAt != null;
    }

    synchronized Api.JobView view(boolean withLog) {
        return new Api.JobView(id, kind.name(), zone == null ? null : zone.toString(), state.name(), edited, script,
                startedAt, finishedAt, withLog ? List.copyOf(log) : null, verify, error);
    }
}
