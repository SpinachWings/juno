package dev.juno.git;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The git environment of design §5.1.1: what an agent's process may reach. The
 * user's credential helpers, SSH agent and tokens in the environment never get through; without a key only local
 * repositories work; with one, HTTPS works and SSH remotes are rewritten to it so the token applies. All of it is
 * environment of that one process: nothing goes into git config or remote URLs.
 */
public final class GitAccess {
    /** Removed from every agent's environment: ways around the key. */
    static final List<String> REMOVED = List.of("SSH_AUTH_SOCK", "SSH_AGENT_PID", "SSH_ASKPASS", "GIT_ASKPASS",
            "GIT_SSH", "GIT_SSH_COMMAND", "GIT_CONFIG_PARAMETERS", "GH_TOKEN", "GITHUB_TOKEN", "GH_ENTERPRISE_TOKEN",
            "GITHUB_ENTERPRISE_TOKEN", "GITLAB_TOKEN", "GLAB_TOKEN", "GITLAB_ACCESS_TOKEN", "CI_JOB_TOKEN");

    private GitAccess() {}

    /** The username that goes with a token over HTTPS. */
    public static String username(boolean gitlab) {
        return gitlab ? "oauth2" : "x-access-token";
    }

    /**
     * An agent's process. {@code hosts}: the hosts of its keys (empty = no key, no host). {@code helper}: the
     * credential helper's script, used with a key.
     */
    public static Map<String, String> forAgent(List<String> hosts, String helper) {
        List<String[]> config = new ArrayList<>();
        config.add(new String[] {"credential.helper", ""}); // an empty value clears the user's helpers
        if (!hosts.isEmpty()) config.add(new String[] {"credential.helper", "!sh '" + helper.replace('\\', '/') + "'"});
        hosts.forEach(h -> config.addAll(rewrites(h)));
        return environment(hosts.isEmpty() ? "file" : "file:https", config);
    }

    /** One git command of Juno's own (the key Test): the same rules, the token only in this child's environment. */
    public static Map<String, String> forToken(String host, boolean gitlab, String token) {
        List<String[]> config = new ArrayList<>();
        config.add(new String[] {"credential.helper", ""});
        String basic = java.util.Base64.getEncoder().encodeToString((username(gitlab) + ":" + token)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        config.add(new String[] {"http.https://" + host + "/.extraHeader", "Authorization: Basic " + basic});
        return environment("https", config);
    }

    private static List<String[]> rewrites(String host) {
        String https = "url.https://" + host + "/.insteadOf";
        return List.of(new String[] {https, "git@" + host + ":"}, new String[] {https, "ssh://git@" + host + "/"});
    }

    private static Map<String, String> environment(String protocols, List<String[]> config) {
        Map<String, String> env = new LinkedHashMap<>();
        REMOVED.forEach(k -> env.put(k, null));
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GCM_INTERACTIVE", "never");
        env.put("GIT_ALLOW_PROTOCOL", protocols);
        env.put("GIT_CONFIG_COUNT", String.valueOf(config.size()));
        for (int i = 0; i < config.size(); i++) {
            env.put("GIT_CONFIG_KEY_" + i, config.get(i)[0]);
            env.put("GIT_CONFIG_VALUE_" + i, config.get(i)[1]);
        }
        return env;
    }

    /**
     * The credential helper for one process: asks Juno, so the token is on neither disk nor in the agent's environment.
     * Plain sh and curl, which Git for Windows brings along. {@code junoToken} is the process's token, the one its
     * {@code mcp.json} already holds; the file goes when the process ends.
     */
    public static String helperScript(String url, String junoToken) {
        return """
                #!/bin/sh
                # Juno: this process's git credentials, asked from Juno. Nothing else in here.
                [ "$1" = get ] || exit 0
                exec curl -s -f --noproxy 127.0.0.1 --max-time 10 -H 'Authorization: Bearer %s' --data-binary @- '%s'
                """.formatted(junoToken, url);
    }

    /** The answer to the helper's {@code get}, or "" unless it asks about HTTPS on one of the hosts. */
    public static String parseHost(String credentialRequest) {
        String protocol = "", host = "";
        for (String line : credentialRequest.split("\r?\n")) {
            if (line.startsWith("protocol=")) protocol = line.substring(9).strip();
            if (line.startsWith("host=")) host = line.substring(5).strip();
        }
        return protocol.equals("https") ? host : "";
    }
}
