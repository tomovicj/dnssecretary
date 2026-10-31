package rs.port53.dnssecretary.verify;

import java.net.InetAddress;
import java.util.List;
import java.util.Optional;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Type;
import rs.port53.dnssecretary.collect.Response;
import rs.port53.dnssecretary.collect.ResolverView;
import rs.port53.dnssecretary.collect.ZoneSnapshot;
import rs.port53.dnssecretary.report.Report;

/**
 * Live verify after a fix (docs/java-proposal.md 3.8): the checks find no critical error,
 * and the validating resolver answers every sample with NOERROR/NXDOMAIN and the AD bit.
 * Pure: works on a freshly collected snapshot.
 */
public final class Verifier {

    private Verifier() {}

    /** One resolver answer: what was asked and whether it validated. */
    public record Answer(Name qname, int qtype, String rcode, boolean ad, boolean ok) {

        @Override
        public String toString() {
            return qname + " " + Type.string(qtype) + ": " + rcode + (ad ? " AD" : "");
        }
    }

    /**
     * @param resolver empty if no resolver is configured (then only the checks count)
     * @param answers  the resolver's answers to the samples
     */
    public record Result(boolean checksOk, Optional<InetAddress> resolver, List<Answer> answers) {

        public boolean resolverOk() {
            return answers.stream().allMatch(Answer::ok);
        }

        public boolean ok() {
            return checksOk && resolverOk();
        }
    }

    public static Result verify(ZoneSnapshot s, Report report) {
        boolean checksOk = report.status() != Report.Status.CRITICAL && report.status() != Report.Status.TOOL_ERROR;
        List<Answer> answers = s.resolver().map(ResolverView::answers).orElse(List.of()).stream()
                .map(Verifier::answer)
                .toList();
        return new Result(checksOk, s.resolver().map(ResolverView::address), answers);
    }

    private static Answer answer(Response r) {
        String rcode = r.ok() ? Rcode.string(r.rcode()) : r.error();
        boolean ok = r.ok() && (r.rcode() == Rcode.NOERROR || r.rcode() == Rcode.NXDOMAIN) && r.ad();
        return new Answer(r.qname(), r.qtype(), rcode, r.ad(), ok);
    }
}
