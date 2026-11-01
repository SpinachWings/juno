package dev.juno.git;

import dev.juno.prompts.Prompts;
import dev.juno.store.AtomicFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Agent worktrees (design §7.8): one {@code git worktree} per agent that edits files, on its own branch, with git hooks
 * that refuse commits, merges and pushes onto the project's base branches. Everything here is local git; no network.
 */
public final class Worktrees {
    private Worktrees() {}

    /** A worktree that now exists. */
    public record Created(Path folder, String branch, String start) {}

    /** The branch checked out in {@code folder}, or the short commit when detached; "" when it isn't a repository. */
    public static String currentBranch(Path folder) {
        try {
            String b = Git.run(folder, "rev-parse", "--abbrev-ref", "HEAD").strip();
            return b.equals("HEAD") ? Git.run(folder, "rev-parse", "--short", "HEAD").strip() : b;
        } catch (IOException e) {
            return "";
        }
    }

    public static boolean hasCommits(Path repo) {
        try {
            Git.run(repo, "rev-parse", "--verify", "HEAD");
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** A branch name made from an agent's name: {@code juno/<name>} in git-safe characters. */
    public static String branchFor(String agentName) {
        String slug = agentName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-").replaceAll("^[-.]+|[-.]+$", "");
        return "juno/" + (slug.isEmpty() ? "agent" : slug);
    }

    /**
     * Creates {@code folder} as a worktree of {@code repo} on a new branch off the repository's current branch. The
     * branch is {@code wanted}, or {@code wanted-2}, {@code wanted-3}… when that name exists.
     */
    public static Created create(Path repo, Path folder, String wanted) throws IOException {
        if (!hasCommits(repo)) {
            throw new IOException("the project has no commits yet, so there is nothing to make a worktree from");
        }
        String start = currentBranch(repo);
        String branch = wanted;
        for (int n = 2; branchExists(repo, branch); n++) branch = wanted + "-" + n;
        Files.createDirectories(folder.getParent());
        Git.run(repo, "worktree", "prune");
        Git.run(repo, "worktree", "add", "-b", branch, folder.toString(), start.isEmpty() ? "HEAD" : start);
        return new Created(folder, branch, start);
    }

    /** Checks an existing branch out into a new worktree (a removed worktree coming back). */
    public static Created attach(Path repo, Path folder, String branch) throws IOException {
        Files.createDirectories(folder.getParent());
        Git.run(repo, "worktree", "prune");
        Git.run(repo, "worktree", "add", folder.toString(), branch);
        return new Created(folder, branch, "");
    }

    public static boolean branchExists(Path repo, String branch) {
        try {
            Git.run(repo, "rev-parse", "--verify", "--quiet", "refs/heads/" + branch);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Where {@code branch} is checked out (a worktree's folder or the main checkout), if anywhere (design §7.8). */
    public static Optional<Path> checkedOutAt(Path repo, String branch) {
        try {
            String list = Git.run(repo, "worktree", "list", "--porcelain");
            Path current = null;
            for (String line : list.split("\n")) {
                if (line.startsWith("worktree ")) current = Path.of(line.substring(9));
                else if (line.equals("branch refs/heads/" + branch) && current != null) return Optional.of(current);
            }
        } catch (IOException e) {
            // not a repository: nowhere
        }
        return Optional.empty();
    }

    /**
     * Why the worktree can't be removed without losing work, or null if it can: uncommitted changes, or commits that
     * exist only on its branch (in no other branch and no remote).
     */
    public static String unsafeToRemove(Path folder) {
        if (!Files.isDirectory(folder)) return null;
        try {
            String status = Git.run(folder, "status", "--porcelain").strip();
            if (!status.isEmpty()) {
                int n = status.split("\n").length;
                return n + " uncommitted change" + (n == 1 ? "" : "s");
            }
            String branch = Git.run(folder, "rev-parse", "--abbrev-ref", "HEAD").strip();
            if (branch.equals("HEAD")) return null;
            String only = Git.run(folder, "rev-list", "--count", "HEAD", "--not", "--exclude=" + branch,
                    "--branches", "--remotes").strip();
            int commits = Integer.parseInt(only);
            if (commits > 0) return commits + " commit" + (commits == 1 ? "" : "s") + " only on " + branch
                    + " (not pushed, not on another branch)";
        } catch (IOException | NumberFormatException e) {
            return "its state couldn't be read (" + e.getMessage() + ")";
        }
        return null;
    }

    /** Removes a worktree; the branch stays. Call {@link #unsafeToRemove} first. */
    public static void remove(Path repo, Path folder) throws IOException {
        if (Files.isDirectory(folder)) {
            try {
                Git.run(repo, "worktree", "remove", "--force", folder.toString());
            } catch (IOException e) {
                deleteTree(folder);
            }
        }
        try {
            Git.run(repo, "worktree", "prune");
        } catch (IOException e) {
            // best effort
        }
    }

    // ---------------------------------------------------------------- protected branches

    /** The branches no agent may commit, merge or push onto: the remote's default branch, else the base branch. */
    public static List<String> baseBranches(Path repo, String createdFrom) {
        List<String> out = new ArrayList<>();
        try {
            String head = Git.run(repo, "symbolic-ref", "--quiet", "--short", "refs/remotes/origin/HEAD").strip();
            if (head.startsWith("origin/")) out.add(head.substring(7));
        } catch (IOException e) {
            // no remote default known
        }
        if (createdFrom != null && !createdFrom.isBlank() && !out.contains(createdFrom) && !createdFrom.startsWith("juno/")) {
            out.add(createdFrom);
        }
        for (String common : List.of("main", "master")) {
            if (!out.contains(common) && branchExists(repo, common)) out.add(common);
        }
        return out;
    }

    /** Client-side hooks Juno forwards to the repository's own (so they still run with our hooks path). */
    static final List<String> HOOKS = List.of("applypatch-msg", "pre-applypatch", "post-applypatch", "pre-commit",
            "pre-merge-commit", "prepare-commit-msg", "commit-msg", "post-commit", "pre-rebase", "post-checkout",
            "post-merge", "pre-push", "pre-auto-gc", "post-rewrite", "reference-transaction", "push-to-checkout",
            "sendemail-validate");

    /**
     * Installs the guard hooks for a worktree: a folder of hooks (outside the worktree) plus a per-worktree
     * {@code core.hooksPath}, so neither the user's checkout nor other worktrees are affected. Safe to repeat; the
     * list of protected branches is rewritten each time.
     */
    public static void installHooks(Path repo, Path worktree, Path hooksDir, List<String> protectedBranches)
            throws IOException {
        String original = originalHooksPath(repo, hooksDir);
        Files.createDirectories(hooksDir);
        AtomicFiles.writeString(hooksDir.resolve("protected-branches"), String.join("\n", protectedBranches) + "\n");
        AtomicFiles.writeString(hooksDir.resolve("original-hooks-path"), original + "\n");
        Map<String, String> sections = Prompts.sections(Prompts.load("results/protected-branch.md"));
        for (Map.Entry<String, String> e : sections.entrySet()) {
            AtomicFiles.writeString(hooksDir.resolve("message-" + e.getKey() + ".txt"), e.getValue() + "\n");
        }
        for (String hook : HOOKS) {
            Path f = hooksDir.resolve(hook);
            AtomicFiles.writeString(f, GuardHook.SCRIPT);
            f.toFile().setExecutable(true, false);
        }
        Git.run(repo, "config", "extensions.worktreeConfig", "true");
        Git.run(worktree, "config", "--worktree", "core.hooksPath", hooksDir.toString().replace('\\', '/'));
    }

    /** Updates the protected branches of an installed worktree (e.g. when a PR/MR base is known: Milestone 5). */
    public static void setProtected(Path hooksDir, List<String> protectedBranches) throws IOException {
        AtomicFiles.writeString(hooksDir.resolve("protected-branches"), String.join("\n", protectedBranches) + "\n");
    }

    /** What {@code core.hooksPath} was before Juno set its own: the repository's hooks folder. */
    private static String originalHooksPath(Path repo, Path hooksDir) throws IOException {
        Path remembered = hooksDir.resolve("original-hooks-path");
        if (Files.isRegularFile(remembered)) {
            String s = Files.readString(remembered).strip();
            if (!s.isEmpty()) return s;
        }
        String configured = "";
        try {
            configured = Git.run(repo, "config", "--get", "core.hooksPath").strip();
        } catch (IOException e) {
            // unset
        }
        Path dir;
        if (!configured.isEmpty()) {
            dir = Path.of(configured);
            if (!dir.isAbsolute()) dir = repo.resolve(configured);
        } else {
            String common = Git.run(repo, "rev-parse", "--git-common-dir").strip();
            Path c = Path.of(common);
            dir = (c.isAbsolute() ? c : repo.resolve(c)).resolve("hooks");
        }
        return dir.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    // ---------------------------------------------------------------- sizes, files

    /** Bytes under {@code dir} (0 if missing). */
    public static long size(Path dir) {
        if (!Files.exists(dir)) return 0;
        try (Stream<Path> walk = Files.walk(dir)) {
            long total = 0;
            for (Path p : (Iterable<Path>) walk::iterator) {
                try {
                    if (Files.isRegularFile(p)) total += Files.size(p);
                } catch (IOException e) {
                    // vanished meanwhile
                }
            }
            return total;
        } catch (IOException | java.io.UncheckedIOException e) {
            return 0;
        }
    }

    static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                p.toFile().setWritable(true);
                Files.deleteIfExists(p);
            }
        }
    }

    /** The hook names and what each kind of refusal is called, for tests. */
    public static Map<String, String> hookKinds() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("pre-commit", "commit");
        m.put("pre-merge-commit", "merge");
        m.put("pre-push", "push");
        m.put("reference-transaction", "update");
        return m;
    }
}
