package dev.juno.git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Review snapshots (design §6.3, §6.5): a detached worktree at a PR/MR's head commit. All worktrees of a repository
 * share one object store, so a commit that's already local needs no network; otherwise it is fetched under
 * {@code refs/juno/review/<reviewer>/<pr>} (never the user's branches), deleted again with the snapshot.
 */
public final class Snapshots {
    /** How long a fetch or a checkout with submodules and LFS may take. */
    private static final long NETWORK_SECONDS = 600;
    private static final int LOCK_TRIES = 5;

    /** What to snapshot. {@code headRef} and {@code baseRef} are the host's refs, e.g. {@code pull/42/head}, {@code main}. */
    public record Request(Path repo, Path folder, String source, String reviewer, int number, String head, String base,
                          String headRef, String baseRef, Map<String, String> env) {}

    /** {@code warnings}: submodules or LFS files it couldn't get; the snapshot still works without them. */
    public record Result(List<String> warnings) {}

    private Snapshots() {}

    private static String ref(String reviewer, int number) {
        return "refs/juno/review/" + reviewer + "/" + number;
    }

    /** The folder for a snapshot: {@code <dir>/<reviewer>-<pr>-<short commit>}. */
    public static Path folder(Path dir, String reviewer, int number, String head) {
        return dir.resolve(reviewer + "-" + number + "-" + head.substring(0, Math.min(10, head.length())));
    }

    public static boolean hasCommit(Path repo, String sha) {
        try {
            Git.run(repo, "cat-file", "-e", sha + "^{commit}");
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Which remote of {@code repo} is the host's repository, or its HTTPS URL when none is. */
    public static String source(Path repo, String host, String path) {
        String wanted = path.toLowerCase(Locale.ROOT);
        try {
            for (String name : Git.run(repo, "remote").split("\n")) {
                if (name.isBlank()) continue;
                String url = Git.run(repo, "remote", "get-url", name).strip().toLowerCase(Locale.ROOT)
                        .replace('\\', '/').replaceFirst("\\.git/?$", "").replaceFirst("/$", "");
                if (url.endsWith("/" + wanted) || url.endsWith(":" + wanted)) return name;
            }
        } catch (IOException e) {
            // not readable: the URL will do
        }
        return "https://" + host + "/" + path + ".git";
    }

    /** Makes the snapshot (a no-op when it exists). Throws with git's message if the commit can't be had. */
    public static Result create(Request r) throws IOException {
        List<String> warnings = new ArrayList<>();
        if (!hasCommit(r.repo(), r.head())) {
            withLockRetries(r.repo(), r.env(), "fetch", "--quiet", "--no-tags", r.source(),
                    "+" + r.headRef() + ":" + ref(r.reviewer(), r.number()));
            if (!hasCommit(r.repo(), r.head())) throw new IOException("the PR/MR's head moved while fetching it");
        }
        if (!hasCommit(r.repo(), r.base())) {
            withLockRetries(r.repo(), r.env(), "fetch", "--quiet", "--no-tags", r.source(),
                    "+refs/heads/" + r.baseRef() + ":" + ref(r.reviewer(), r.number()) + "-base");
        }
        if (Files.isDirectory(r.folder())) return new Result(warnings);
        Files.createDirectories(r.folder().getParent());
        Git.run(r.repo(), "worktree", "prune");
        withLockRetries(r.repo(), r.env(), "worktree", "add", "--quiet", "--detach", r.folder().toString(), r.head());
        if (Files.exists(r.folder().resolve(".gitmodules"))) {
            try {
                Git.run(r.folder(), r.env(), NETWORK_SECONDS, "submodule", "update", "--init", "--recursive", "--quiet");
            } catch (IOException e) {
                warnings.add("submodules: " + e.getMessage());
            }
        }
        if (usesLfs(r.folder())) {
            try {
                Git.run(r.folder(), r.env(), NETWORK_SECONDS, "lfs", "pull");
            } catch (IOException e) {
                warnings.add("Git LFS files: " + e.getMessage());
            }
        }
        return new Result(warnings);
    }

    private static boolean usesLfs(Path folder) {
        try {
            return Files.isRegularFile(folder.resolve(".gitattributes"))
                    && Files.readString(folder.resolve(".gitattributes")).contains("filter=lfs");
        } catch (IOException | java.io.UncheckedIOException e) {
            return false;
        }
    }

    /** "If another agent's git command holds a lock, retry for a few seconds before reporting" (design §6.5). */
    private static void withLockRetries(Path repo, Map<String, String> env, String... args) throws IOException {
        for (int i = 1; ; i++) {
            try {
                Git.run(repo, env, NETWORK_SECONDS, args);
                return;
            } catch (IOException e) {
                String m = String.valueOf(e.getMessage());
                if (i >= LOCK_TRIES || !(m.contains(".lock") || m.contains("cannot lock"))) throw e;
                try {
                    Thread.sleep(1_500);
                } catch (InterruptedException x) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** Removes the snapshot and its refs. Throws if the folder is still in use (a process running in it on Windows). */
    public static void remove(Path folder, String reviewer, int number) throws IOException {
        Path repo = repoOf(folder);
        if (repo != null) Worktrees.remove(repo, folder);
        else if (Files.isDirectory(folder)) Worktrees.deleteTree(folder);
        if (repo != null) {
            for (String suffix : List.of("", "-base")) {
                try {
                    Git.run(repo, "update-ref", "-d", ref(reviewer, number) + suffix);
                } catch (IOException e) {
                    // there was no fetch
                }
            }
        }
    }

    /** The repository a snapshot belongs to, from its own {@code .git} file; null if it's beyond telling. */
    static Path repoOf(Path folder) {
        try {
            Path common = Path.of(Git.run(folder, "rev-parse", "--path-format=absolute", "--git-common-dir").strip());
            return common.getParent();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** "<reviewer>-<pr>-<commit>" back into its parts (reviewer ids have no dash in them), or null. */
    public static String[] parseName(String name) {
        String[] p = name.split("-");
        return p.length == 3 && p[1].matches("\\d+") ? p : null;
    }
}
