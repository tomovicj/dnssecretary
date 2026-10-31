package rs.port53.dnssecretary.dns;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.xbill.DNS.DNSKEYRecord;
import org.xbill.DNS.DSRecord;
import org.xbill.DNS.Record;
import rs.port53.dnssecretary.collect.ChainLink;

/**
 * Validates the chain of trust from the anchor to the parent of the checked zone
 * (root DNSKEY against the anchor, DS of test. signed by the root, test. DNSKEY against that DS …).
 * In the lab this part is always correct, but a real tool must not assume it.
 * Pure function over snapshot data.
 */
public final class ChainValidator {

    /** @param problems in Serbian, for the report; empty when secure */
    public record Result(boolean secure, List<String> problems) {}

    private ChainValidator() {}

    public static Result validate(List<Record> anchors, List<ChainLink> chain, Instant now) {
        List<String> problems = new ArrayList<>();
        if (anchors.isEmpty()) {
            problems.add("nema trust anchor-a");
            return new Result(false, problems);
        }
        List<DNSKEYRecord> parentKeys = List.of();
        for (int i = 0; i < chain.size(); i++) {
            ChainLink link = chain.get(i);
            String zone = link.zone().toString();
            List<DNSKEYRecord> keys = link.dnskey().records(DNSKEYRecord.class);
            if (keys.isEmpty()) {
                problems.add("zona " + zone + " nema DNSKEY");
                break;
            }

            List<DNSKEYRecord> entry;
            if (i == 0) {
                entry = keys.stream().filter(k -> anchors.stream().anyMatch(a -> anchorMatches(a, k))).toList();
                if (entry.isEmpty()) {
                    problems.add("nijedan DNSKEY zone " + zone + " ne odgovara trust anchor-u");
                    break;
                }
            } else {
                String parent = chain.get(i - 1).zone().toString();
                if (link.ds().isEmpty()) {
                    problems.add("zona " + parent + " nema DS za " + zone);
                    break;
                }
                if (!Dnssec.isSignedBy(link.ds(), parentKeys, now)) {
                    problems.add("DS za " + zone + " nema ispravan potpis zone " + parent);
                }
                List<DSRecord> ds = link.ds().records(DSRecord.class);
                entry = keys.stream().filter(k -> ds.stream().anyMatch(d -> Dnssec.dsMatches(d, k))).toList();
                if (entry.isEmpty()) {
                    problems.add("nijedan DNSKEY zone " + zone + " ne odgovara DS-u u zoni " + parent);
                    break;
                }
            }
            if (!Dnssec.isSignedBy(link.dnskey(), entry, now)) {
                problems.add("DNSKEY skup zone " + zone + " nije ispravno potpisan ključem na koji pokazuje DS/anchor");
            }
            parentKeys = keys;
        }
        return new Result(problems.isEmpty(), problems);
    }

    private static boolean anchorMatches(Record anchor, DNSKEYRecord key) {
        if (anchor instanceof DSRecord ds) {
            return Dnssec.dsMatches(ds, key);
        }
        return anchor instanceof DNSKEYRecord k && k.equals(key);
    }
}
