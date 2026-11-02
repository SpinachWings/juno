package dev.juno.ui;

import dev.juno.app.AppContext;
import dev.juno.app.MainWindow;
import dev.juno.git.Git;
import dev.juno.model.Agent;
import dev.juno.model.Key;
import dev.juno.model.Profile;
import dev.juno.model.Project;
import dev.juno.provider.claude.ClaudeCodeProvider;
import dev.juno.runtime.LiveAgent;
import dev.juno.store.JunoHome;
import dev.juno.store.TokenStore;
import dev.juno.ui.settings.SettingsWindow;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javax.imageio.ImageIO;

/**
 * Not a unit test: drives the real window for Milestone 5 against a scratch Juno home, writing a screenshot after each
 * step. Usage: {@code java -cp ... dev.juno.ui.ReviewScreens <scratch dir> <output dir> <scenario> [repo checkout] [owner/repo]}.
 * Scenarios: {@code ui} (no CLI, no network: the Keys tab, the pipeline card, the inbox), {@code nokey} (a haiku coder
 * with no key on a scratch repository whose remote is HTTPS: refused and 🔑), {@code coder}, {@code review} and
 * {@code tell} (the sandbox; they post, so they run only when asked to). The token comes from {@code JUNO_SANDBOX_TOKEN}.
 */
public final class ReviewScreens {
    private static Path out;
    private static AppContext ctx;
    private static MainWindow window;
    private static Stage stage;

    public static void main(String[] args) throws Exception {
        Path scratch = Path.of(args[0]);
        out = Path.of(args[1]);
        String scenario = args[2];
        Files.createDirectories(out);
        TokenStore env = new TokenStore() {
            public boolean vaultAvailable() { return true; }
            public String vaultProblem() { return ""; }
            public String save(String id, String t) { return TokenStore.keyringReference(id); }
            public void delete(String id) {}
            public Optional<String> resolve(String r) { return Optional.ofNullable(System.getenv("JUNO_SANDBOX_TOKEN")); }
        };
        JunoHome home = new JunoHome(scratch.resolve("home"));
        ctx = new AppContext(home, env, new ClaudeCodeProvider(scratch.resolve("claude-hooks"), home));
        ctx.skills.refreshBundled(ctx.settings);
        ctx.settings.system.firstRunDone = true;
        for (Profile p : ctx.settings.profiles) {
            p.model = "haiku";
            p.effort = "low";
        }
        if (ctx.settings.keys.isEmpty()) {
            Key k = new Key();
            k.id = "sandbox";
            k.label = "sandbox";
            k.token = "env:JUNO_SANDBOX_TOKEN";
            ctx.settings.keys.add(k);
            Key g = new Key();
            g.id = "gitlab";
            g.label = "a GitLab key";
            g.kind = Key.Kind.GITLAB;
            g.host = "gitlab.com";
            g.token = "env:GITLAB_MCP";
            g.gitToken = "env:GITLAB_GIT";
            ctx.settings.keys.add(g);
        }
        if (args.length > 3 && ctx.settings.projects.isEmpty()) {
            Project p = new Project();
            p.id = "sandbox";
            p.name = "sandbox";
            p.folder = args[3];
            p.remote = args.length > 4 ? args[4] : "";
            ctx.settings.projects.add(p);
        }
        ctx.save();
        ctx.runtime.start();
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        started.await();
        Platform.setImplicitExit(false);
        run(() -> {
            stage = new Stage();
            window = new MainWindow(ctx, stage);
            javafx.scene.Scene s = new javafx.scene.Scene(window.root(), 1400, 900);
            Theme.apply(s);
            stage.setScene(s);
            stage.show();
        });
        Thread.sleep(800);
        try {
            switch (scenario) {
                case "ui" -> ui();
                case "test" -> test(args[4]);
                case "nokey" -> noKey();
                case "coder" -> coder();
                case "review" -> review();
                case "tell" -> tell();
                default -> throw new IllegalArgumentException(scenario);
            }
        } finally {
            ctx.runtime.shutdown();
            System.exit(0);
        }
    }

    /** The Test button's work on the sandbox key: reads, and writes that can't succeed. */
    private static void test(String repo) {
        for (var line : ctx.runtime.host().test(ctx.settings.keys.get(0), repo)) {
            System.out.println((line.ok() == null ? "? " : line.ok() ? "OK " : "NO ") + line.what()
                    + (line.detail().isBlank() ? "" : ": " + line.detail()));
        }
    }

    private static void ui() throws Exception {
        Agent asks = ctx.runtime.create("rita", "reviewer", null, "#7CFFB2", "sandbox", null);
        asks.review = new Agent.Review();
        asks.review.target = "#42";
        asks.review.phase = Agent.Review.Phase.ASK;
        asks.review.ask = Agent.Review.Ask.NEEDS_PERSON;
        asks.review.askText = "Pipelines on #42 need a person: CI (pull_request). Approve them on GitHub, then press Continue.";
        asks.status = Agent.Status.AWAITING;
        asks.reason = Agent.Reason.FAILED;
        asks.statusDetail = asks.review.askText;
        Agent failed = ctx.runtime.create("sam", "reviewer", null, "#FFD166", "sandbox", null);
        failed.review = new Agent.Review();
        failed.review.target = "#43";
        failed.review.phase = Agent.Review.Phase.ASK;
        failed.review.ask = Agent.Review.Ask.FAILED;
        failed.review.askText = "Pipelines failed on #43: build (push), lint. No review until there's a new commit.";
        failed.status = Agent.Status.AWAITING;
        failed.reason = Agent.Reason.FAILED;
        failed.statusDetail = failed.review.askText;
        Agent wait = ctx.runtime.create("wes", "reviewer", null, "#6FD6FF", "sandbox", null);
        wait.status = Agent.Status.WAITING;
        wait.statusDetail = "pipelines on #44";
        wait.statusSince = System.currentTimeMillis() - 12 * 60_000L;
        Agent key = ctx.runtime.create("nina", "coder", null, "#FF4FD8");
        key.status = Agent.Status.AWAITING;
        key.reason = Agent.Reason.KEY;
        key.statusDetail = "No GitHub key is set for nina, so I can't reach acme/api — set one in nina's options menu.";
        for (Agent a : java.util.List.of(asks, failed, wait, key)) ctx.runtime.saved(a);
        Thread.sleep(1_000);
        shot("80-board-review-states");
        run(() -> SettingsWindow.show(ctx, stage, SettingsWindow.Page.KEYS));
        Thread.sleep(800);
        shot("81-keys-github", SettingsWindow.current());
        run(() -> {
            for (javafx.scene.Node n : SettingsWindow.current().getScene().getRoot().lookupAll(".side-list")) {
                if (n instanceof javafx.scene.control.ListView<?> list && !list.getItems().isEmpty()
                        && list.getItems().get(0) instanceof Key) list.getSelectionModel().select(1);
            }
        });
        Thread.sleep(500);
        shot("81-keys-gitlab", SettingsWindow.current());
        run(() -> {
            SettingsWindow.current().hide();
            window.board().showAgent(asks);
            window.board().toggleMaximise(asks.id);
        });
        Thread.sleep(800);
        shot("80-pipeline-card");
        run(() -> window.inbox().open());
        Thread.sleep(600);
        for (javafx.stage.Window w : javafx.stage.Window.getWindows()) {
            if (w instanceof javafx.stage.Popup) shot("80-inbox", w);
        }
    }

    /** A coder without a key: commits work, pushing doesn't, and it stops at 🔑. */
    private static void noKey() throws Exception {
        Agent bob = ctx.runtime.create("bob", "coder", "sandbox", "#6FD6FF");
        ctx.runtime.send(bob, "Run exactly this one shell command and report its output: git push -u origin HEAD", "user");
        waitFor(() -> !bob.turnOpen && bob.status != Agent.Status.IDLE && bob.status != Agent.Status.WORKING, 180);
        Thread.sleep(800);
        System.out.println("status=" + bob.statusText() + " detail=" + bob.statusDetail);
        System.out.println("env worktree folder=" + ctx.runtime.folder(bob));
        System.out.println("remote branches after: " + Git.run(ctx.runtime.folder(bob), "branch", "-a"));
        shot("82-no-key-stop");
    }

    private static Agent coderAgent() {
        return ctx.runtime.byName("cody").orElseGet(() -> ctx.runtime.create("cody", "coder", "sandbox", "#6FD6FF", "sandbox", null));
    }

    private static Agent reviewerAgent() {
        return ctx.runtime.byName("rita").orElseGet(() -> ctx.runtime.create("rita", "reviewer", "sandbox", "#7CFFB2", "sandbox", null));
    }

    private static void say(Agent a, String text) throws Exception {
        waitFor(() -> !a.turnOpen, 300);
        ctx.runtime.send(a, text, "user");
        Thread.sleep(500);
        waitFor(() -> !a.turnOpen, 400);
        Thread.sleep(800);
        LiveAgent l = ctx.runtime.live(a.id);
        System.out.println("--- " + a.name + " [" + a.statusText() + "]: " + l.lastReply);
    }

    private static void coder() throws Exception {
        Agent c = coderAgent();
        Agent r = reviewerAgent();
        ctx.runtime.orchestration().linkByUser(c, "rita", true);
        say(c, "Task (branch agreed: spike/m5). Create that branch, add one line 'hello from Juno' to a new file notes/juno-m5.txt, "
                + "commit, push it, open a PR into main with the host's MCP tools. There are no build commands: skip the "
                + "tests and superpowers' reviews, it's one line. Then follow your rule about linked reviewers.");
        shot("83-coder-opened-pr");
        System.out.println("rita: " + r.statusText() + " review=" + (r.review == null ? "none" : r.review.target));
        waitFor(() -> r.status == Agent.Status.WAITING || r.review != null || !r.turnOpen, 120);
        Thread.sleep(3_000);
        shot("84-rita-told");
    }

    private static void review() throws Exception {
        Agent r = reviewerAgent();
        // The linked workflow: the coder's one message is what starts the review (design §6.4).
        if (r.review == null) {
            ctx.runtime.newConversation(r);
            say(coderAgent(), System.getenv("JUNO_REVIEW_ASK"));
        }
        waitFor(() -> r.review != null, 120);
        if (r.review == null) {
            System.out.println("rita opened no review: " + ctx.runtime.live(r.id).lastReply);
            return;
        }
        shot("85-waiting");
        waitFor(() -> r.review != null && r.review.phase == Agent.Review.Phase.READY && !r.turnOpen, 900);
        waitFor(() -> !r.turnOpen, 400);
        Thread.sleep(800);
        LiveAgent l = ctx.runtime.live(r.id);
        System.out.println("--- rita [" + r.statusText() + "]: " + l.lastReply);
        shot("86-comments-for-approval");
        System.out.println("snapshot=" + (r.review == null ? "" : r.review.snapshot));
    }

    /** Types into an agent's pane, as the user would: {@code JUNO_TELL_WHO}, {@code JUNO_TELL_WHAT}, and a shot's name. */
    private static void tell() throws Exception {
        Agent a = ctx.runtime.byName(System.getenv("JUNO_TELL_WHO")).orElseThrow();
        waitFor(() -> !a.turnOpen, 300);
        var outcome = new dev.juno.runtime.PaneCommands(ctx.runtime).handle(a, System.getenv("JUNO_TELL_WHAT"));
        System.out.println("command: " + outcome);
        Thread.sleep(800);
        waitFor(() -> !a.turnOpen, 500);
        Thread.sleep(800);
        // A review waiting for pipelines finishes by itself and starts another turn.
        if (a.review != null && a.review.phase != Agent.Review.Phase.READY && a.review.phase != Agent.Review.Phase.ASK) {
            waitFor(() -> a.review == null || a.review.phase == Agent.Review.Phase.READY || a.review.phase == Agent.Review.Phase.ASK, 400);
            Thread.sleep(800);
            waitFor(() -> !a.turnOpen, 400);
        }
        Thread.sleep(800);
        System.out.println("--- " + a.name + " [" + a.statusText() + "] review="
                + (a.review == null ? "none" : a.review.phase + " head " + a.review.headSha) + ":\n" + ctx.runtime.live(a.id).lastReply);
        window.board().showAgent(a);
        Thread.sleep(600);
        run(() -> window.board().toggleMaximise(a.id));
        Thread.sleep(700);
        shot(System.getenv("JUNO_TELL_SHOT"));
    }

    private static void waitFor(BooleanSupplier c, int seconds) throws InterruptedException {
        long until = System.currentTimeMillis() + seconds * 1000L;
        while (!c.getAsBoolean() && System.currentTimeMillis() < until) Thread.sleep(100);
    }

    private static void run(Runnable r) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                r.run();
            } finally {
                done.countDown();
            }
        });
        done.await(30, TimeUnit.SECONDS);
    }

    private static void shot(String name) throws Exception {
        shot(name, stage);
    }

    private static void shot(String name, javafx.stage.Window w) throws Exception {
        WritableImage[] img = new WritableImage[1];
        run(() -> img[0] = w.getScene().snapshot(null));
        int width = (int) img[0].getWidth(), height = (int) img[0].getHeight();
        BufferedImage b = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        PixelReader px = img[0].getPixelReader();
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) b.setRGB(x, y, px.getArgb(x, y));
        ImageIO.write(b, "png", out.resolve(name + ".png").toFile());
    }
}
