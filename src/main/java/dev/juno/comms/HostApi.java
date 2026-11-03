package dev.juno.comms;

import com.fasterxml.jackson.databind.JsonNode;
import dev.juno.store.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** What Juno itself asks a host, over REST: the key's Test button (design §5.1). Agents never come through here. */
public interface HostApi {
    /** The host refused or failed. {@code status} is the HTTP status, 0 if there was no answer. */
    final class HostException extends IOException {
        public final int status;
        /** GitHub's {@code x-accepted-github-permissions} on a 403: the permission that's missing. */
        public final String wanted;

        public HostException(int status, String message, String wanted) {
            super(message);
            this.status = status;
            this.wanted = wanted == null ? "" : wanted;
        }

        public boolean denied() {
            return status == 401 || status == 403;
        }
    }

    /** A line of the key's Test result. {@code ok} null: couldn't tell, or nothing to check. */
    record TestLine(String what, Boolean ok, String detail) {}

    /** Checks the key's REST access; {@code repo} blank tests only who the token is (design §5.1). */
    List<TestLine> test(String repo);

    /** A small JSON-over-HTTP client with the token in its header. The token never reaches an exception. */
    final class Rest {
        private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        private final String base;
        private final Map<String, String> headers;

        public Rest(String base, Map<String, String> headers) {
            this.base = base;
            this.headers = headers;
        }

        public JsonNode get(String path) throws IOException {
            return send("GET", path, null);
        }

        public JsonNode post(String path, String body) throws IOException {
            return send("POST", path, body);
        }

        private JsonNode send(String method, String path, String body) throws IOException {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30));
            headers.forEach(b::header);
            if (body != null) b.header("Content-Type", "application/json");
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> r;
            try {
                r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new HostException(0, "interrupted", null);
            } catch (IOException e) {
                throw new HostException(0, "no answer from the host (" + e.getClass().getSimpleName() + ")", null);
            }
            if (r.statusCode() >= 300) {
                String why = "";
                try {
                    why = Json.MAPPER.readTree(r.body()).path("message").asText("");
                } catch (IOException e) {
                    // not JSON
                }
                throw new HostException(r.statusCode(), "HTTP " + r.statusCode() + (why.isBlank() ? "" : ": " + why),
                        r.headers().firstValue("x-accepted-github-permissions").orElse(null));
            }
            return r.body().isBlank() ? Json.MAPPER.createObjectNode() : Json.MAPPER.readTree(r.body());
        }
    }
}
