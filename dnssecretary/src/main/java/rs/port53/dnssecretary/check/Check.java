package rs.port53.dnssecretary.check;

import java.util.List;
import java.util.Set;
import rs.port53.dnssecretary.catalog.ErrorId;
import rs.port53.dnssecretary.collect.ZoneSnapshot;
import rs.port53.dnssecretary.model.Finding;

/**
 * One check over a snapshot. Pure: no network, no files, time only from ctx.clock().
 * Register new checks in {@link CheckRunner#defaultChecks()}.
 */
public interface Check {

    /** The errors this check can report. Unit tests for these IDs run as soon as it is registered. */
    Set<ErrorId> detects();

    List<Finding> run(ZoneSnapshot snapshot, CheckContext ctx);

    /** Name in reports and error messages. */
    default String name() {
        return getClass().getSimpleName();
    }
}
