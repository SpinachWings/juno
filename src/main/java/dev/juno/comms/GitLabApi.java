package dev.juno.comms;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** GitLab's REST API with the key's git and REST token, for the Test button (design §5.1). Not run against a real GitLab yet. */
public final class GitLabApi implements HostApi {
    private final Rest rest;

    /** {@code base}: {@code https://<host>/api/v4}. */
    public GitLabApi(String base, String token) {
        this.rest = new Rest(base, Map.of("PRIVATE-TOKEN", token, "User-Agent", "juno"));
    }

    public static String apiBase(String host) {
        return "https://" + host + "/api/v4";
    }

    private static String project(String repo) {
        return "/projects/" + enc(repo);
    }

    @Override
    public List<TestLine> test(String repo) {
        List<TestLine> out = new ArrayList<>();
        try {
            out.add(new TestLine("The git and REST token works", true, "signed in as "
                    + rest.get("/user").path("username").asText("?")));
        } catch (IOException e) {
            out.add(new TestLine("The git and REST token works", false, e.getMessage()));
            return out;
        }
        try {
            List<String> scopes = new ArrayList<>();
            rest.get("/personal_access_tokens/self").path("scopes").forEach(s -> scopes.add(s.asText()));
            if (!scopes.isEmpty()) {
                out.add(new TestLine("Scope api (read MRs and issues)", scopes.contains("api"), String.join(", ", scopes)));
                out.add(new TestLine("Scope read_repository (fetch)", scopes.contains("read_repository")
                        || scopes.contains("api"), ""));
                out.add(new TestLine("Scope write_repository (push)",
                        scopes.contains("write_repository"), ""));
            }
        } catch (IOException e) {
            out.add(new TestLine("Token scopes", null, "project and group tokens may not show them"));
        }
        if (repo == null || repo.isBlank()) {
            out.add(new TestLine("Project permissions", null, "name a project to check them"));
            return out;
        }
        JsonNode meta;
        try {
            meta = rest.get(project(repo));
            out.add(new TestLine("Read the project", true, ""));
        } catch (IOException e) {
            out.add(new TestLine("Read the project", false, e.getMessage()));
            return out;
        }
        out.add(read("Read merge requests", project(repo) + "/merge_requests?per_page=1"));
        out.add(read("Read issues", project(repo) + "/issues?per_page=1"));
        int access = Math.max(meta.at("/permissions/project_access/access_level").asInt(0),
                meta.at("/permissions/group_access/access_level").asInt(0));
        if (access > 0) out.add(new TestLine("Comment (Reporter or above)", access >= 20, "access level " + access));
        return out;
    }

    private TestLine read(String what, String path) {
        try {
            rest.get(path);
            return new TestLine(what, true, "");
        } catch (IOException e) {
            return new TestLine(what, false, e.getMessage());
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
