package rs.port53.dnssecretary.report;

import java.io.IOException;
import java.io.OutputStream;
import rs.port53.dnssecretary.json.DnsJson;

/** The report as JSON (check --json). The web report will read the same structure. */
public final class JsonReport {

    private JsonReport() {}

    public static void write(Report r, OutputStream out) throws IOException {
        DnsJson.mapper().writer()
                .without(com.fasterxml.jackson.core.JsonGenerator.Feature.AUTO_CLOSE_TARGET)
                .writeValue(out, r);
        out.write('\n');
        out.flush();
    }
}
