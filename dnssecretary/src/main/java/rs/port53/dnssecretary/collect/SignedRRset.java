package rs.port53.dnssecretary.collect;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.xbill.DNS.Name;
import org.xbill.DNS.RRSIGRecord;
import org.xbill.DNS.RRset;
import org.xbill.DNS.Record;
import org.xbill.DNS.Type;
import rs.port53.dnssecretary.json.DnsJson;

/**
 * An RRset together with the RRSIGs that cover it. Empty when the server had no such RRset.
 *
 * @param ttl as received (from a resolver: the remaining TTL in its cache)
 */
public record SignedRRset(
        Name name,
        @JsonSerialize(using = DnsJson.TypeSerializer.class)
        @JsonDeserialize(using = DnsJson.TypeDeserializer.class)
        int type,
        long ttl,
        List<Record> records,
        List<RRSIGRecord> sigs) {

    public SignedRRset {
        Objects.requireNonNull(name);
        records = records == null ? List.of() : List.copyOf(records);
        sigs = sigs == null ? List.of() : List.copyOf(sigs);
    }

    public static SignedRRset empty(Name name, int type) {
        return new SignedRRset(name, type, 0, List.of(), List.of());
    }

    public static SignedRRset of(RRset rrset) {
        return new SignedRRset(rrset.getName(), rrset.getType(), rrset.getTTL(), rrset.rrs(false), rrset.sigs());
    }

    @JsonIgnore
    public boolean isEmpty() {
        return records.isEmpty();
    }

    @JsonIgnore
    public boolean isSigned() {
        return !sigs.isEmpty();
    }

    /** The records cast to their type, e.g. {@code dnskey.records(DNSKEYRecord.class)}. */
    public <T extends Record> List<T> records(Class<T> cls) {
        return records.stream().filter(cls::isInstance).map(cls::cast).toList();
    }

    /** As a dnsjava RRset (records + sigs), e.g. for {@code DNSSEC.verify(rrset, sig, key, now)}. */
    public RRset toRRset() {
        RRset r = new RRset();
        records.forEach(r::addRR);
        sigs.forEach(r::addRR);
        return r;
    }

    /**
     * Groups loose records (a zone file, a message section) into RRsets.
     * Each RRSIG goes to the RRset of its type covered; an RRSIG without its RRset
     * becomes an empty SignedRRset with only sigs. Order: first appearance.
     */
    public static List<SignedRRset> group(Collection<? extends Record> all) {
        record Key(Name name, int type) {}
        Map<Key, List<Record>> recs = new LinkedHashMap<>();
        Map<Key, List<RRSIGRecord>> sigs = new LinkedHashMap<>();
        Map<Key, Long> ttls = new LinkedHashMap<>();
        for (Record r : all) {
            if (r instanceof RRSIGRecord sig) {
                Key k = new Key(sig.getName(), sig.getTypeCovered());
                recs.computeIfAbsent(k, x -> new ArrayList<>());
                sigs.computeIfAbsent(k, x -> new ArrayList<>()).add(sig);
            } else if (r.getType() != Type.OPT) {
                Key k = new Key(r.getName(), r.getType());
                recs.computeIfAbsent(k, x -> new ArrayList<>()).add(r);
                ttls.merge(k, r.getTTL(), Math::min);
            }
        }
        List<SignedRRset> out = new ArrayList<>();
        recs.forEach((k, rs) -> out.add(new SignedRRset(k.name(), k.type(), ttls.getOrDefault(k, 0L), rs,
                sigs.getOrDefault(k, List.of()))));
        return out;
    }

  }
