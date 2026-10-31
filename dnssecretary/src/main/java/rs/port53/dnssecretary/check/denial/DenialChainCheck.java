package rs.port53.dnssecretary.check.denial;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.xbill.DNS.Name;
import org.xbill.DNS.NSECRecord;
import org.xbill.DNS.Type;

import rs.port53.dnssecretary.catalog.ErrorId;
import rs.port53.dnssecretary.check.Check;
import rs.port53.dnssecretary.check.CheckContext;
import rs.port53.dnssecretary.collect.NameserverView;
import rs.port53.dnssecretary.collect.Response;
import rs.port53.dnssecretary.collect.SignedRRset;
import rs.port53.dnssecretary.collect.ZoneSnapshot;
import rs.port53.dnssecretary.model.Finding;
import rs.port53.dnssecretary.model.Severity;

/** D1, D2, D3, D6: the NSEC chain, NSEC type bitmaps, signed denial proofs, NSEC and NSEC3 mixed. */
public class DenialChainCheck implements Check {

    @Override
    public Set<ErrorId> detects() {
        return Set.of(ErrorId.D1, ErrorId.D2, ErrorId.D3, ErrorId.D6);
    }

    @Override
    public List<Finding> run(ZoneSnapshot snapshot, CheckContext ctx) {
        List<Finding> findings = new ArrayList<>();

        // D6: NSEC and NSEC3 mixed in one zone
        boolean hasNsec = snapshot.zoneRRsets().stream().anyMatch(sr -> sr.type() == Type.NSEC);
        boolean hasNsec3 = snapshot.zoneRRsets().stream().anyMatch(sr -> sr.type() == Type.NSEC3);

        if (hasNsec && hasNsec3) {
            findings.add(new Finding(
                    ErrorId.D6, Severity.RISK, snapshot.zone(), Type.NSEC,
                    "The zone has both NSEC and NSEC3 records.",
                    Map.of()
            ));
            return findings;
        }

        // D3: a negative answer without an NSEC/NSEC3 proof, or with an unsigned one
        for (NameserverView ns : snapshot.nameservers()) {
            for (Response denial : ns.denials()) {
                List<SignedRRset> nsecProofs = denial.authority(Type.NSEC);
                List<SignedRRset> nsec3Proofs = denial.authority(Type.NSEC3);

                boolean hasNsecProof = nsecProofs != null && !nsecProofs.isEmpty();
                boolean hasNsec3Proof = nsec3Proofs != null && !nsec3Proofs.isEmpty();

                if (!hasNsecProof && !hasNsec3Proof) {
                    findings.add(new Finding(
                            ErrorId.D3, Severity.CRITICAL, denial.qname(), denial.qtype(),
                            "Negative answer from " + ns.address().getHostAddress() + " has no NSEC/NSEC3 proof.",
                            Map.of("nameserver", ns.address().getHostAddress())
                    ));
                    continue;
                }

                boolean missingSig = false;
                if (hasNsecProof) {
                    for (SignedRRset p : nsecProofs) {
                        if (p.sigs().isEmpty()) missingSig = true;
                    }
                }
                if (hasNsec3Proof) {
                    for (SignedRRset p : nsec3Proofs) {
                        if (p.sigs().isEmpty()) missingSig = true;
                    }
                }

                if (missingSig) {
                    findings.add(new Finding(
                            ErrorId.D3, Severity.CRITICAL, denial.qname(), denial.qtype(),
                            "Negative answer from " + ns.address().getHostAddress() + " has an unsigned NSEC/NSEC3 proof.",
                            Map.of("nameserver", ns.address().getHostAddress())
                    ));
                }
            }
        }

        if (snapshot.zoneRRsets().isEmpty()) {
            return findings;
        }

        // All owners of NSEC records (to find a broken chain)
        Set<Name> nsecOwners = snapshot.zoneRRsets().stream()
                .filter(sr -> sr.type() == Type.NSEC)
                .map(SignedRRset::name)
                .collect(Collectors.toSet());

        // D1 and D2: the NSEC chain and type bitmaps
        for (SignedRRset sr : snapshot.zoneRRsets()) {
            if (sr.type() == Type.NSEC) {
                for (NSECRecord nsec : sr.records(NSECRecord.class)) {

                    // D1: broken chain (next points to a name without an NSEC)
                    if (!nsecOwners.contains(nsec.getNext())) {
                        findings.add(new Finding(
                                ErrorId.D1, Severity.CRITICAL, nsec.getName(), Type.NSEC,
                                "NSEC points to a name without an NSEC record (broken chain): " + nsec.getNext(),
                                Map.of("next", nsec.getNext().toString())
                        ));
                    }

                    // D2: wrong type list
                    Set<Integer> actualTypes = new HashSet<>();
                    for (SignedRRset other : snapshot.zoneRRsets()) {
                        if (other.name().equals(nsec.getName())) {
                            actualTypes.add(other.type());
                        }
                    }
                    actualTypes.add(Type.NSEC);
                    actualTypes.add(Type.RRSIG);

                    int[] bitmapArray = nsec.getTypes();
                    Set<Integer> bitmap = Arrays.stream(bitmapArray).boxed().collect(Collectors.toSet());

                    if (!bitmap.equals(actualTypes)) {
                        findings.add(new Finding(
                                ErrorId.D2, Severity.CRITICAL, nsec.getName(), Type.NSEC,
                                "NSEC type bitmap does not match the records at this name.",
                                Map.of("expected", actualTypes.toString(), "actual", bitmap.toString())
                        ));
                    }
                }
            }
        }

        return findings;
    }
}
