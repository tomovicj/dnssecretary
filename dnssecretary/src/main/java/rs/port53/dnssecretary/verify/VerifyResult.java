package rs.port53.dnssecretary.verify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.xbill.DNS.Type;
import rs.port53.dnssecretary.model.Diagnosis;
import rs.port53.dnssecretary.report.Report;

/**
 * The outcome of a verify as plain data: what "verify --json" prints, what the sandbox hands
 * back and what the web app shows (the "verify" object of a Job in the API).
 *
 * @param remaining IDs of the errors still found (risks too)
 * @param ok        no critical error and the resolver answered every sample with AD
 */
public record VerifyResult(String zone, boolean checksOk, List<String> remaining, List<Answer> answers, boolean ok) {

    private static final ObjectMapper JSON = new ObjectMapper();

    public record Answer(String qname, String qtype, String rcode, boolean ad, boolean ok) {}

    public VerifyResult {
        remaining = List.copyOf(remaining);
        answers = List.copyOf(answers);
    }

    public static VerifyResult of(Report report, Verifier.Result v) {
        return new VerifyResult(report.zone().toString(), v.checksOk(),
                report.diagnoses().stream().map(Diagnosis::id).map(Enum::name).toList(),
                v.answers().stream()
                        .map(a -> new Answer(a.qname().toString(), Type.string(a.qtype()), a.rcode(), a.ad(), a.ok()))
                        .toList(),
                v.ok());
    }

    /** One line of JSON. */
    public String toJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static VerifyResult fromJson(String json) throws JsonProcessingException {
        return JSON.readValue(json, VerifyResult.class);
    }
}
