package dev.juno.ui;

import dev.juno.app.AppContext;
import dev.juno.app.MainWindow;
import dev.juno.model.Agent;
import dev.juno.model.Profile;
import dev.juno.model.Project;
import dev.juno.provider.claude.ClaudeCodeProvider;
import dev.juno.runtime.AgentRuntime;
import dev.juno.runtime.LiveAgent;
import dev.juno.store.JunoHome;
import dev.juno.store.TokenStore;
import dev.juno.ui.settings.SettingsWindow;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javax.imageio.ImageIO;

/**
 * Not a unit test: drives the real window against a scratch Juno home and a scratch git repository, writing a screenshot
 * after each step. Usage: {@code java -cp ... dev.juno.ui.BoardScreens <scratch dir> <output dir> <scenario>}.
 * Scenarios: {@code layout} (no CLI), {@code crash1} + {@code crash2} (several agents cut off mid-turn, then recovery; same scratch dir).
 */
public final class BoardScreens {
    private static Path out;
    private static Path repo;
    private static AppContext ctx;
    private static MainWindow window;
    private static Stage stage;

    public static void main(String[] args) throws Exception {
        Path scratch = Path.of(args[0]);
        out = Path.of(args[1]);
        String scenario = args[2];
        Files.createDirectories(out);
        repo = scratch.resolve("repo");
        if (!Files.exists(repo.resolve(".git"))) {
            Files.createDirectories(repo);
            Files.writeString(repo.resolve("greet.py"), "def greet(name):\n    return \"Hello \" + name\n");
            git(repo, "init", "-q", "-b", "main");
            git(repo, "add", ".");
            git(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "init");
        }
        TokenStore memory = new TokenStore() {
            public boolean vaultAvailable() { return true; }
            public String vaultProblem() { return ""; }
            public String save(String id, String t) { return TokenStore.keyringReference(id); }
            public void delete(String id) {}
            public Optional<String> resolve(String r) { return Optional.empty(); }
        };
        JunoHome home = new JunoHome(scratch.resolve("home"));
        ctx = new AppContext(home, memory, new ClaudeCodeProvider(scratch.resolve("claude-hooks"), home));
        ctx.skills.refreshBundled(ctx.settings);
        ctx.settings.system.firstRunDone = true;
        if (ctx.settings.projects.isEmpty()) {
            Project p = new Project();
            p.id = "p1";
            p.name = "greeter";
            p.folder = repo.toString();
            ctx.settings.projects.add(p);
            dev.juno.model.Key k = new dev.juno.model.Key();
            k.id = "k1";
            k.label = "my github";
            ctx.settings.keys.add(k);
        }
        if (ctx.settings.profiles.stream().noneMatch(p -> p.name.equals("editor"))) {
            Profile e = ctx.settings.profiles.stream().filter(p -> p.name.equals("coder")).findFirst().orElseThrow()
                    .duplicate("editor");
            e.model = "haiku";
            e.effort = "low";
            e.systemPrompt = "You are {{agent_name}}. Do exactly what you are asked, with as few steps as possible, "
                    + "and answer in one short sentence.";
            e.allowedTools.clear();
            e.allowedTools.addAll(List.of("Read", "Grep", "Glob"));
            e.disallowedTools.clear();
            ctx.settings.profiles.add(e);
            Profile t = ctx.settings.profiles.stream().filter(p -> p.name.equals("searcher")).findFirst().orElseThrow()
                    .duplicate("tester");
            t.model = "haiku";
            t.effort = "low";
            ctx.settings.profiles.add(t);
        }
        ctx.save();
        ctx.runtime.start();

        Platform.startup(() -> {});
        run(() -> openWindow(1400, 900));
        Thread.sleep(1000);
        switch (scenario) {
            case "layout" -> layout();
            case "crash1" -> crash1();
            case "crash2" -> crash2();
            default -> throw new IllegalArgumentException(scenario);
        }
        ctx.runtime.shutdown();
        Platform.exit();
        System.exit(0);
    }

    private static void openWindow(int w, int h) {
        stage = new Stage();
        window = new MainWindow(ctx, stage);
        Scene s = new Scene(window.root(), w, h);
        Theme.apply(s);
        stage.setScene(s);
        stage.show();
    }

    // ---------------------------------------------------------------- scenarios

    private static void layout() throws Exception {
        shot("01-first-run");
        click("Cancel");
        Thread.sleep(300);
        shot("01b-cancelled-juno-alone");
        Agent alpha = create("alpha", "editor", "p1");
        Agent beta = create("beta", "tester", null);
        Agent gamma = create("gamma", "tester", "p1");
        Thread.sleep(600);
        gamma.rules.add(new Agent.AgentRule("Bash(npm test:*)", "npm test, with any arguments", "npm test -- --watch"));
        long now = System.currentTimeMillis();
        for (int i = 0; i < 2; i++) {
            Agent.Conversation c = new Agent.Conversation();
            c.id = "old" + i;
            c.sessionId = "no-such-session-" + i; // Claude Code has no transcript: greyed out
            c.started = now - (i + 2) * 86_400_000L;
            c.ended = c.started + 3_600_000L;
            c.firstTask = i == 0 ? "Rename greet() to hello() everywhere" : "Look at why the build is slow";
            c.tokens = 48_000L * (i + 1);
            c.summary = i == 0 ? "Renamed in 3 files; tests pass." : "It's the integration tests.";
            gamma.conversations.add(c);
        }
        ctx.runtime.saved(gamma);
        Thread.sleep(600);
        shot("02-three-agents");

        run(() -> window.board().split(beta.id, true));
        Thread.sleep(500);
        shot("03-split-setup-new");
        click("Cancel");
        Thread.sleep(400);

        run(() -> window.board().hide(gamma.id));
        Thread.sleep(500);
        shot("04-gamma-hidden");
        run(() -> window.board().split(beta.id, false));
        Thread.sleep(400);
        run(() -> toggle("Show a hidden agent"));
        Thread.sleep(400);
        shot("05-split-setup-hidden");
        click("Show");
        Thread.sleep(600);
        shot("06-gamma-shown-in-the-new-cell");

        run(() -> window.board().swap("juno", alpha.id));
        Thread.sleep(500);
        shot("07-swapped-juno-and-alpha");

        run(() -> window.board().toggleMaximise(alpha.id));
        Thread.sleep(500);
        shot("08-alpha-maximised");
        run(() -> window.board().toggleMaximise(alpha.id));
        Thread.sleep(400);

        run(() -> window.board().toggleMaximise(gamma.id));
        run(() -> window.board().paneOf(gamma).toggleOptions());
        Thread.sleep(700);
        shot("09-gamma-options");
        run(() -> window.board().paneOf(gamma).toggleOptions());
        run(() -> window.board().toggleMaximise(gamma.id));

        beta.status = Agent.Status.AWAITING;
        beta.reason = Agent.Reason.QUESTION;
        ctx.runtime.live(beta.id).lastReply = "I found two greeters.\n\n❓ Which one should I change: greet.py or hello.py?";
        gamma.status = Agent.Status.AWAITING;
        gamma.reason = Agent.Reason.INTERRUPTED;
        gamma.statusDetail = "Interrupted: Juno crashed while you were working.";
        gamma.unread = true;
        ctx.runtime.saved(beta);
        ctx.runtime.saved(gamma);
        Thread.sleep(500);
        run(() -> window.inbox().open());
        Thread.sleep(700);
        shotAll("10-inbox");
        run(() -> window.inbox().button().fire());
        Thread.sleep(300);

        run(() -> stage.setWidth(900));
        run(() -> stage.setHeight(620));
        Thread.sleep(700);
        shot("11-narrow-window-scrolls");
        run(() -> stage.setWidth(1400));
        run(() -> stage.setHeight(900));

        run(() -> SettingsWindow.show(ctx, stage, SettingsWindow.Page.SYSTEM));
        Thread.sleep(1500);
        run(() -> ((javafx.scene.control.ScrollPane) ((javafx.scene.control.TabPane) SettingsWindow.current().getScene()
                .getRoot()).getTabs().get(1).getContent()).setVvalue(1.0));
        Thread.sleep(500);
        shot("12-settings-storage", SettingsWindow.current());
        run(() -> SettingsWindow.current().close());

        // the layout is saved: a second window on the same home shows the same panes
        List<String> before = window.board().layout().leaves();
        String json = Files.readString(home().layoutFile());
        run(() -> {
            stage.close();
            openWindow(1400, 900);
        });
        Thread.sleep(800);
        System.out.println("LAYOUT before " + before + " after " + window.board().layout().leaves()
                + " same=" + before.equals(window.board().layout().leaves()));
        System.out.println(json);
        shot("13-reopened");
    }

    private static void crash1() throws Exception {
        ctx.settings.system.maxAgentsWorking = 4;
        Agent a = create("crashA", "editor", "p1");
        Agent b = create("crashB", "tester", "p1");
        Agent c = create("crashC", "editor", "p1");
        Thread.sleep(500);
        type(a, "Write out the numbers from 1 to 300, one per line, in plain text, nothing else.");
        type(b, "Write out the numbers from 1 to 300, one per line, in plain text, nothing else.");
        type(c, "Write out the numbers from 1 to 300, one per line, in plain text, nothing else.");
        waitFor(() -> ctx.runtime.live(a.id).contextTokens > 0 || ctx.runtime.live(b.id).contextTokens > 0, 90);
        Thread.sleep(1500);
        shot("30-crash-before");
        System.out.println("CRASH now (no shutdown): " + a.statusText() + " / " + b.statusText() + " / " + c.statusText());
        for (Agent x : List.of(a, b, c)) {
            ProcessHandle.of(ctx.runtime.live(x.id).pid()).ifPresent(h -> {
                h.descendants().forEach(ProcessHandle::destroyForcibly);
                h.destroyForcibly();
            });
        }
        Runtime.getRuntime().halt(1);
    }

    private static void crash2() throws Exception {
        Thread.sleep(1200);
        shot("31-crash-after-restart");
        for (Agent a : ctx.runtime.agents()) System.out.println("AFTER " + a.name + " " + a.statusText() + " " + a.interruptReason);
        for (Agent a : ctx.runtime.agents()) type(a, "/resume");
        approveUntilIdle(ctx.runtime.agents().toArray(Agent[]::new));
        shot("32-crash-resumed");
    }

    // ---------------------------------------------------------------- helpers

    private static JunoHome home() {
        return ctx.home;
    }

    private static Agent create(String name, String profile, String project) throws Exception {
        Agent[] a = new Agent[1];
        run(() -> a[0] = ctx.runtime.byName(name).orElseGet(() -> ctx.runtime.create(name, profile, project, null)));
        Thread.sleep(300);
        return a[0];
    }

    private static void type(Agent a, String text) throws Exception {
        run(() -> {
            window.board().showAgent(a);
            window.board().paneOf(a).submit(text);
        });
        Thread.sleep(300);
    }

    /** Answers permission requests (through the real buttons when the pane is up) until every agent has finished. */
    private static void approveUntilIdle(Agent... agents) throws Exception {
        long until = System.currentTimeMillis() + 240_000;
        Thread.sleep(2000);
        while (System.currentTimeMillis() < until) {
            boolean busy = false;
            for (Agent a : agents) {
                LiveAgent l = ctx.runtime.live(a.id);
                if (a.turnOpen || a.status == Agent.Status.QUEUED || !l.pendingPermissions().isEmpty()) busy = true;
                if (!l.pendingPermissions().isEmpty()) {
                    Thread.sleep(500);
                    run(() -> {
                        window.board().showAgent(a);
                        String err = ctx.runtime.answer(a, null, AgentRuntime.Answer.ALLOW_ONCE, null);
                        if (err != null) System.out.println("answer: " + err);
                    });
                }
            }
            if (!busy) break;
            Thread.sleep(600);
        }
        Thread.sleep(1500);
    }

    private static void waitFor(BooleanSupplier c, int seconds) throws InterruptedException {
        long until = System.currentTimeMillis() + seconds * 1000L;
        while (!c.getAsBoolean() && System.currentTimeMillis() < until) Thread.sleep(300);
    }

    private static void click(String text) throws Exception {
        run(() -> {
            Button found = null; // the newest cell is last
            for (Node n : stage.getScene().getRoot().lookupAll(".button")) {
                if (n instanceof Button b && b.getText().equals(text) && !b.isDisabled()) found = b;
            }
            if (found != null) found.fire();
            else System.out.println("no button " + text);
        });
    }

    private static void toggle(String text) {
        javafx.scene.control.ToggleButton found = null;
        for (Node n : stage.getScene().getRoot().lookupAll(".toggle-button")) {
            if (n instanceof javafx.scene.control.ToggleButton b && b.getText().equals(text)) found = b;
        }
        if (found != null) found.setSelected(true);
    }

    private static String git(Path where, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("git", "-C", where.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String s = new String(p.getInputStream().readAllBytes()).strip();
        p.waitFor();
        return s;
    }

    private static void run(Runnable r) throws Exception {
        CountDownLatch l = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                r.run();
            } finally {
                l.countDown();
            }
        });
        l.await(30, TimeUnit.SECONDS);
    }

    private static void shot(String name) throws Exception {
        shot(name, stage);
    }

    private static void shotAll(String name) throws Exception {
        shot(name, stage);
        List<javafx.stage.Window> popups = new java.util.ArrayList<>();
        run(() -> javafx.stage.Window.getWindows().stream()
                .filter(w -> w instanceof javafx.stage.Popup && w.isShowing()).forEach(popups::add));
        for (int i = 0; i < popups.size(); i++) shot(name + "-popup" + i, popups.get(i));
    }

    private static void shot(String name, javafx.stage.Window w) throws Exception {
        WritableImage[] img = new WritableImage[1];
        run(() -> img[0] = w.getScene().snapshot(null));
        PixelReader pr = img[0].getPixelReader();
        int width = (int) img[0].getWidth(), height = (int) img[0].getHeight();
        BufferedImage bi = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) bi.setRGB(x, y, pr.getArgb(x, y));
        ImageIO.write(bi, "png", out.resolve(name + ".png").toFile());
        System.out.println("wrote " + name);
    }
}
