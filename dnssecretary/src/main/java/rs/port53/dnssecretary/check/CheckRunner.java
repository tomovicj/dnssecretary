package rs.port53.dnssecretary.check;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import rs.port53.dnssecretary.catalog.ErrorId;
import rs.port53.dnssecretary.check.algorithms.AlgorithmPolicyCheck;
import rs.port53.dnssecretary.check.algorithms.KeyStrengthCheck;
import rs.port53.dnssecretary.check.delegation.DelegationCheck;
import rs.port53.dnssecretary.check.delegation.DsPolicyCheck;
import rs.port53.dnssecretary.check.denial.DenialChainCheck;
import rs.port53.dnssecretary.check.denial.Nsec3PolicyCheck;
import rs.port53.dnssecretary.check.rollover.CacheConsistencyCheck;
import rs.port53.dnssecretary.check.rollover.CdsCheck;
import rs.port53.dnssecretary.check.servers.NameserverConsistencyCheck;
import rs.port53.dnssecretary.check.servers.TransportCheck;
import rs.port53.dnssecretary.check.signatures.SignatureCoverageCheck;
import rs.port53.dnssecretary.check.signatures.SignatureCryptoCheck;
import rs.port53.dnssecretary.check.signatures.SignatureValidityCheck;
import rs.port53.dnssecretary.check.signatures.TtlCheck;
import rs.port53.dnssecretary.collect.ZoneSnapshot;
import rs.port53.dnssecretary.model.Finding;

/** Runs all checks over a snapshot. A check that throws doesn't stop the others. */
public final class CheckRunner {

    /**
     * @param failures "CheckName: message" for every check that threw (a bug in the tool, exit 3)
     */
    public record Result(List<Finding> findings, List<String> failures) {}

    private final List<Check> checks;

    public CheckRunner(List<Check> checks) {
        this.checks = List.copyOf(checks);
    }

    /**
     * All checks of the tool. Add yours here (one line, in your group's section),
     * see docs/java-proposal.md 3.5 for which class detects what.
     */
    public static List<Check> defaultChecks() {
        return List.of(
                // A, C – signatures, TTL, algorithms, key strength (role 2)
                new SignatureValidityCheck(),     // A1, A2, A6
                new SignatureCoverageCheck(),     // A3
                new SignatureCryptoCheck(),       // A4, A5
                new TtlCheck(),                   // A7
                new AlgorithmPolicyCheck(),       // C1, C2
                new KeyStrengthCheck(),           // C3

                // B, E – delegation, DS, CDS, cache and rollover (role 3)
                new DelegationCheck(),            // B1–B4, B6, B8
                new DsPolicyCheck(),              // B5, B7
                new CacheConsistencyCheck(),      // E1, E3
                new CdsCheck(),                   // E4

                // D, F – NSEC/NSEC3, name server consistency, transport (role 4)
                new DenialChainCheck(),           // D1, D2, D3, D6
                new Nsec3PolicyCheck(),           // D4, D5
                new NameserverConsistencyCheck(), // F1
                new TransportCheck()              // F2
        );
    }

    public static CheckRunner withDefaultChecks() {
        return new CheckRunner(defaultChecks());
    }

    public List<Check> checks() {
        return checks;
    }

    /** The errors at least one registered check can report. */
    public Set<ErrorId> detectable() {
        Set<ErrorId> out = EnumSet.noneOf(ErrorId.class);
        checks.forEach(c -> out.addAll(c.detects()));
        return out;
    }

    public Result run(ZoneSnapshot snapshot, CheckContext ctx) {
        List<Finding> findings = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (Check c : checks) {
            try {
                findings.addAll(c.run(snapshot, ctx));
            } catch (RuntimeException e) {
                failures.add(c.name() + ": " + e);
            }
        }
        return new Result(findings, failures);
    }
}
