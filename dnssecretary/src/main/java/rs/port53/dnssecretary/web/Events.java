package rs.port53.dnssecretary.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.javalin.http.sse.SseClient;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * The SSE stream /api/events: every open browser gets every event.
 * Events: "zone" (a ZoneRow that changed), "job" (a job's new state, no log), "log" (one log line).
 */
final class Events {

    /** Something that receives events; the SSE clients in production, a list in tests. */
    interface Sink {
        void send(String event, String json);
    }

    private final Set<Sink> sinks = new CopyOnWriteArraySet<>();

    void add(SseClient client) {
        client.keepAlive();
        Sink s = (event, json) -> client.sendEvent(event, json);
        sinks.add(s);
        client.onClose(() -> sinks.remove(s));
    }

    void add(Sink sink) {
        sinks.add(sink);
    }

    void publish(String event, Object data) {
        String json;
        try {
            json = Api.JSON.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        for (Sink s : sinks) {
            try {
                s.send(event, json);
            } catch (RuntimeException e) {
                sinks.remove(s);   // a closed browser tab
            }
        }
    }
}
