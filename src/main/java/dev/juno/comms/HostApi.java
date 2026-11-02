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
import java.util.Optional;

/**
 * What Juno itself asks a host, over REST (design §6.6): PR/MR details for reviews, pipeline polling, required checks,
 * who started a thread, and the key's Test button. Agents never come through here.
 */
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

    record PullRequest(int number, String state, String headSha, String baseSha, String baseRef, String headRef) {
        public boolean open() {
            return state.equals("open");
        }
    }

    enum CheckState { PASSED, FAILED, PENDING, NEEDS_PERSON }

    /**
     * One check, status or workflow run on the head commit. {@code event} tells apart runs of one workflow;
     * {@code run}: a workflow run rather than a check (never itself "required").
     */
    record Check(String name, String event, CheckState state, boolean required, boolean run) {}

    /**
     * Everything on the head commit. {@code missingRequired}: required checks with no result yet;
     * {@code configured}: the repository has pipelines that may simply not have started on this commit.
     */
    record Checks(List<Check> checks, List<String> missingRequired, boolean configured) {}

    /** A line of the key's Test result. {@code ok} null: couldn't tell, or nothing to check. */
    record TestLine(String what, Boolean ok, String detail) {}

    /** "#42" or "!42". */
    String label(int number);

    /** The git ref a PR/MR's head can be fetched from (design §6.5). */
    String headRef(int number);

    PullRequest pullRequest(String repo, int number) throws IOException;

    /** The open PR/MR whose head is {@code branch}. */
    Optional<PullRequest> openFor(String repo, String branch) throws IOException;

    Checks checks(String repo, PullRequest pr) throws IOException;

    /** The first comment of a review thread, to tell who started it (design §6.6). */
    String threadStarter(String repo, int number, String threadId) throws IOException;

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
