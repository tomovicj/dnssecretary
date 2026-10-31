package rs.port53.dnssecretary.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.util.Map;
import org.xbill.DNS.Name;
import rs.port53.dnssecretary.catalog.ErrorId;
import rs.port53.dnssecretary.json.DnsJson;

/**
 * One raw fact a check found. Diagnoser turns findings into diagnoses.
 *
 * @param owner    where: e.g. www.a1-expired.test.
 * @param type     the RR type at owner (org.xbill.DNS.Type), e.g. Type.A
 * @param evidence the raw fact, e.g. the RRSIG in presentation format
 * @param facts    named values for the report, e.g. expiration=2026-10-22T14:03:00Z, nameserver=10.53.0.12
 */
public record Finding(
        ErrorId id,
        Severity severity,
        Name owner,
        @JsonSerialize(using = DnsJson.TypeSerializer.class)
        @JsonDeserialize(using = DnsJson.TypeDeserializer.class)
        int type,
        String evidence,
        Map<String, String> facts) {

    public Finding {
        facts = facts == null ? Map.of() : Map.copyOf(facts);
    }
}
