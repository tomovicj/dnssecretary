package rs.port53.dnssecretary.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.net.InetAddress;
import org.xbill.DNS.DNSKEYRecord;
import org.xbill.DNS.DSRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.RRSIGRecord;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;
import rs.port53.dnssecretary.dns.Addresses;
import rs.port53.dnssecretary.dns.Records;

/**
 * Jackson setup for dnsjava types. Everything is written in presentation format,
 * so snapshots and JSON reports are readable and diffable:
 * Name → "www.good.test.", Record → "www.good.test.\t3600\tIN\tA\t10.53.0.80",
 * RR type → "DNSKEY", rcode → "NXDOMAIN", InetAddress → "10.53.0.11".
 */
public final class DnsJson {

    private DnsJson() {}

    public static ObjectMapper mapper() {
        return new ObjectMapper()
                .registerModule(new Jdk8Module())
                .registerModule(new JavaTimeModule())
                .registerModule(module())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public static SimpleModule module() {
        SimpleModule m = new SimpleModule("dnsjava");
        m.addSerializer(Name.class, new ToStringSerializer<>());
        m.addDeserializer(Name.class, new NameDeserializer());
        m.addSerializer(Record.class, new ToStringSerializer<>());
        addRecord(m, Record.class);
        addRecord(m, RRSIGRecord.class);
        addRecord(m, DNSKEYRecord.class);
        addRecord(m, DSRecord.class);
        m.addSerializer(InetAddress.class, new AddressSerializer());
        m.addDeserializer(InetAddress.class, new AddressDeserializer());
        return m;
    }

    private static <T extends Record> void addRecord(SimpleModule m, Class<T> cls) {
        m.addDeserializer(cls, new RecordDeserializer<>(cls));
    }

    static final class ToStringSerializer<T> extends JsonSerializer<T> {
        @Override
        public void serialize(T value, JsonGenerator gen, SerializerProvider p) throws IOException {
            gen.writeString(value.toString());
        }
    }

    static final class NameDeserializer extends JsonDeserializer<Name> {
        @Override
        public Name deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            try {
                return Name.fromString(p.getValueAsString(), Name.root);
            } catch (TextParseException e) {
                throw ctx.weirdStringException(p.getValueAsString(), Name.class, e.getMessage());
            }
        }
    }

    static final class RecordDeserializer<T extends Record> extends JsonDeserializer<T> {
        private final Class<T> cls;

        RecordDeserializer(Class<T> cls) {
            this.cls = cls;
        }

        @Override
        public T deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            String s = p.getValueAsString();
            Record r = Records.parse(s);
            if (!cls.isInstance(r)) {
                throw ctx.weirdStringException(s, cls, "not a " + cls.getSimpleName());
            }
            return cls.cast(r);
        }
    }

    static final class AddressSerializer extends JsonSerializer<InetAddress> {
        @Override
        public void serialize(InetAddress value, JsonGenerator gen, SerializerProvider p) throws IOException {
            gen.writeString(value.getHostAddress());
        }
    }

    static final class AddressDeserializer extends JsonDeserializer<InetAddress> {
        @Override
        public InetAddress deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            return Addresses.parse(p.getValueAsString());
        }
    }

    /** For int fields holding an RR type (org.xbill.DNS.Type): written as its mnemonic. */
    public static final class TypeSerializer extends JsonSerializer<Integer> {
        @Override
        public void serialize(Integer value, JsonGenerator gen, SerializerProvider p) throws IOException {
            gen.writeString(Type.string(value));
        }
    }

    public static final class TypeDeserializer extends JsonDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            int t = Type.value(p.getValueAsString());
            if (t < 0) {
                throw ctx.weirdStringException(p.getValueAsString(), Integer.class, "unknown RR type");
            }
            return t;
        }
    }

    /** For int fields holding an rcode (org.xbill.DNS.Rcode); -1 (no response) is written as null. */
    public static final class RcodeSerializer extends JsonSerializer<Integer> {
        @Override
        public void serialize(Integer value, JsonGenerator gen, SerializerProvider p) throws IOException {
            if (value < 0) {
                gen.writeNull();
            } else {
                gen.writeString(Rcode.string(value));
            }
        }
    }

    public static final class RcodeDeserializer extends JsonDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            int r = Rcode.value(p.getValueAsString());
            if (r < 0) {
                throw ctx.weirdStringException(p.getValueAsString(), Integer.class, "unknown rcode");
            }
            return r;
        }

        @Override
        public Integer getNullValue(DeserializationContext ctx) {
            return -1;
        }
    }
}
