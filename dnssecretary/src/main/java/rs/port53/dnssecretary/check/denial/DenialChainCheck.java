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

public class DenialChainCheck implements Check {

    @Override
    public Set<ErrorId> detects() {
        return Set.of(ErrorId.D1, ErrorId.D2, ErrorId.D3, ErrorId.D6);
    }

    @Override
    public List<Finding> run(ZoneSnapshot snapshot, CheckContext ctx) {
        List<Finding> findings = new ArrayList<>();

        // --- D6: NSEC i NSEC3 pomešani u istoj zoni ---
        boolean hasNsec = snapshot.zoneRRsets().stream().anyMatch(sr -> sr.type() == Type.NSEC);
        boolean hasNsec3 = snapshot.zoneRRsets().stream().anyMatch(sr -> sr.type() == Type.NSEC3);

        if (hasNsec && hasNsec3) {
            Name zoneName = snapshot.zoneRRsets().isEmpty() ? Name.root : snapshot.zoneRRsets().get(0).name();
            findings.add(new Finding(
                    ErrorId.D6, Severity.RISK, zoneName, Type.NSEC,
                    "Zona sadrži i NSEC i NSEC3 zapise (promešano).",
                    Map.of()
            ));
            return findings;
        }

        // --- D3: Negativan odgovor bez NSEC/NSEC3 ili bez potpisa ---
        for (NameserverView ns : snapshot.nameservers()) {
            for (Response denial : ns.denials()) {
                List<SignedRRset> nsecProofs = denial.authority(Type.NSEC);
                List<SignedRRset> nsec3Proofs = denial.authority(Type.NSEC3);

                boolean hasNsecProof = nsecProofs != null && !nsecProofs.isEmpty();
                boolean hasNsec3Proof = nsec3Proofs != null && !nsec3Proofs.isEmpty();

                if (!hasNsecProof && !hasNsec3Proof) {
                    findings.add(new Finding(
                            ErrorId.D3, Severity.CRITICAL, Name.root, Type.NSEC,
                            "Negativan odgovor sa servera " + ns.address() + " nema NSEC/NSEC3 dokaz.",
                            Map.of("nameserver", ns.address().toString())
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
                            ErrorId.D3, Severity.CRITICAL, Name.root, Type.NSEC,
                            "Negativan odgovor sa servera " + ns.address() + " ima NSEC/NSEC3 dokaz, ali bez potpisa.",
                            Map.of("nameserver", ns.address().toString())
                    ));
                }
            }
        }

        if (snapshot.zoneRRsets().isEmpty()) {
            return findings;
        }

        // Skup svih imena koja imaju NSEC zapis (za proveru pokidanog lanca)
        Set<Name> nsecOwners = snapshot.zoneRRsets().stream()
                .filter(sr -> sr.type() == Type.NSEC)
                .map(SignedRRset::name)
                .collect(Collectors.toSet());

        // --- D1 i D2: Provera NSEC lanaca i bitmapa ---
        for (SignedRRset sr : snapshot.zoneRRsets()) {
            if (sr.type() == Type.NSEC) {
                for (NSECRecord nsec : sr.records(NSECRecord.class)) {

                    // D1: Pokidan lanac (next ne pokazuje na sledeći NSEC zapis)
                    if (!nsecOwners.contains(nsec.getNext())) {
                        findings.add(new Finding(
                                ErrorId.D1, Severity.CRITICAL, nsec.getName(), Type.NSEC,
                                "NSEC pokazuje na ime koje nema NSEC zapis (pokidan lanac): " + nsec.getNext(),
                                Map.of("next", nsec.getNext().toString())
                        ));
                    }

                    // D2: Pogrešna lista tipova
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
                                "NSEC bitmapa ne odgovara stvarnim zapisima.",
                                Map.of("expected", actualTypes.toString(), "actual", bitmap.toString())
                        ));
                    }
                }
            }
        }

        return findings;
    }
}