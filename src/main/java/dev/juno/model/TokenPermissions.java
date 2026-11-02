package dev.juno.model;

/** What a token needs to be allowed to do, per host kind (design §5.1), for the Keys tab. */
public final class TokenPermissions {
    private TokenPermissions() {}

    public static String forKind(Key.Kind kind) {
        return kind == Key.Kind.GITLAB ? GITLAB : GITHUB;
    }

    private static final String GITHUB = """
            Use a fine-grained personal access token (Settings → Developer settings). Resource owner: the \
            repository's owner; select the repositories agents will use. A classic token with the "repo" scope also works.

            Repository permissions:
            • Metadata: read (always on)
            • Contents: read and write (read is enough for a reviewer)
            • Pull requests: read and write
            • Actions: read
            • Checks: read
            • Commit statuses: read
            • Administration: read (optional; lets Juno see which checks are required)

            An organisation may have to approve the token before it works.""";

    private static final String GITLAB = """
            A GitLab key needs two tokens, because GitLab's MCP server rejects ordinary tokens.

            MCP token: a fine-grained personal access token with the MCP tool resource and the Execute permission. \
            Legacy personal, project and group tokens are refused by the MCP server, even with the api scope.

            Git and REST token (git over HTTPS, pipeline status): a personal, project or group access token with the \
            scopes api, read_repository and write_repository (a reviewer needs api and read_repository only).

            Self-managed GitLab needs 18.3 or later with the MCP server enabled.""";
}
