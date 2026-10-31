package rs.port53.dnssecretary.check.denial;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.xbill.DNS.NSEC3PARAMRecord;
import org.xbill.DNS.NSEC3Record;
import org.xbill.DNS.Type;

import rs.port53.dnssecretary.catalog.ErrorId;
import rs.port53.dnssecretary.check.Check;
import rs.port53.dnssecretary.check.CheckContext;
import rs.port53.dnssecretary.collect.NameserverView;
import rs.port53.dnssecretary.collect.SignedRRset;
import rs.port53.dnssecretary.collect.ZoneSnapshot;
import rs.port53.dnssecretary.model.Finding;
import rs.port53.dnssecretary.model.Severity;

public class Nsec3PolicyCheck implements Check {

    @Override
    public Set<ErrorId> detects() {
        return Set.of(ErrorId.D4, ErrorId.D5);
    }

    @Override
    public List<Finding> run(ZoneSnapshot snapshot, CheckContext ctx) {
        List<Finding> findings = new ArrayList<>();

        if (snapshot.nameservers().isEmpty()) return findings;
        NameserverView ns = snapshot.nameservers().get(0);
        
        if (ns.nsec3param() == null || ns.nsec3param().rrset() == null || ns.nsec3param().rrset().isEmpty()) {
            return findings; 
        }
        
        SignedRRset paramSet = ns.nsec3param().rrset();
        NSEC3PARAMRecord param = paramSet.records(NSEC3PARAMRecord.class).stream().findFirst().orElse(null);
        if (param == null) return findings;

        byte[] paramSalt = param.getSalt() == null ? new byte[0] : param.getSalt();
        boolean hasSalt = paramSalt.length > 0;

        if (param.getIterations() > 0 || hasSalt) {
            findings.add(new Finding(
                ErrorId.D4, Severity.RISK, param.getName(), Type.NSEC3PARAM,
                "NSEC3 parametri krše RFC 9276 (iterations=" + param.getIterations() + 
                ", salt=" + (hasSalt ? "prisutan" : "-") + ").",
                Map.of(
                    "iterations", String.valueOf(param.getIterations()),
                    "salt", hasSalt ? "prisutan" : "-"
                )
            ));
        }

        for (SignedRRset sr : snapshot.zoneRRsets()) {
            if (sr.type() == Type.NSEC3) {
                for (NSEC3Record nsec3 : sr.records(NSEC3Record.class)) {
                    byte[] nsec3Salt = nsec3.getSalt() == null ? new byte[0] : nsec3.getSalt();
                    
                    if (nsec3.getIterations() != param.getIterations() ||
                        !Arrays.equals(nsec3Salt, paramSalt)) {
                        
                        findings.add(new Finding(
                            ErrorId.D5, Severity.CRITICAL, nsec3.getName(), Type.NSEC3,
                            "NSEC3 parametri (iteracije/salt) ne odgovaraju NSEC3PARAM zapisu.",
                            Map.of(
                                "nsec3_iter", String.valueOf(nsec3.getIterations()),
                                "param_iter", String.valueOf(param.getIterations())
                            )
                        ));
                        return findings; 
                    }
                }
            }
        }

        return findings;
    }
}
