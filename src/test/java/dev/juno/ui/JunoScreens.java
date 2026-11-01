package dev.juno.ui;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.juno.app.AppContext;
import dev.juno.app.MainWindow;
import dev.juno.model.Agent;
import dev.juno.model.Profile;
import dev.juno.provider.claude.ClaudeCodeProvider;
import dev.juno.runtime.AgentRuntime;
import dev.juno.runtime.LiveAgent;
import dev.juno.store.Json;
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
 * Not a unit test: drives the real window for Milestone 4 against a scratch Juno home, writing a screenshot after each
 * step. Usage: {@code java -cp ... dev.juno.ui.JunoScreens <scratch dir> <output dir> <scenario>}. Scenarios:
 * {@code ui} (no CLI: Juno's cards, the inbox, can-talk-to, /status, /getcontextfrom, settings) and {@code live}
 * (Juno and two agents on haiku / low: an unnamed task approved and reported, a fresh start, a refused unlinked
 * message, a link opened through Juno, a hop-limit stop).
 */
public final class JunoScreens {
    private static Path out;
    private static AppContext ctx;
    private static MainWindow window;
    private static Stage stage;

    public static void main(String[] args) throws Exception {
        Path scratch = Path.of(args[0]);
        out = Path.of(args[1]);
        String scenario = args[2];
        Files.createDirectories(out);
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
        if (scenario.equals("ui")) ctx.settings.system.claudePath = scratch.resolve("no-claude").toString();
        for (Profile p : ctx.settings.profiles) {
            if (p.name.equals("orchestrator")) {
                p.model = "haiku";
                p.effort = "low";
            }
        }
        if (ctx.settings.profiles.stream().noneMatch(p -> p.name.equals("tester"))) {
            Profile t = ctx.settings.profiles.stream().filter(p -> p.name.equals("searcher")).findFirst().orElseThrow()
                    .duplicate("tester");
            t.model = "haiku";
            t.effort = "low";
            t.systemPrompt = "You are {{agent_name}}. Do exactly what you are asked, with as few steps as possible, "
                    + "and answer in one short sentence.";
            t.allowedTools.removeIf(x -> !x.startsWith("mcp__juno__"));
            ctx.settings.profiles.add(t);
        }
        ctx.save();
        ctx.runtime.start();

        Platform.startup(() -> {});
        run(() -> openWindow(1500, 950));
        Thread.sleep(1000);
        switch (scenario) {
            case "ui" -> ui();
            case "live" -> live();
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

    // ---------------------------------------------------------------- no CLI

    private static void ui() throws Exception {
        click("Cancel");
        Agent bob = create("bob", "tester");
        Agent jim = create("jim", "tester");
        Agent sue = create("sue", "tester");
        type(bob, "/cantalktojim");
        Agent juno = ctx.runtime.juno();
        juno(juno, "assign_task", Json.MAPPER.createObjectNode().put("to", "sue")
                .put("task", "Find out why the nightly build is slow and report the three slowest steps.").put("fresh", false));
        juno(juno, "assign_task", Json.MAPPER.createObjectNode().put("to", "bob")
                .put("task", "Write the release notes for 2.3 from the merged PRs.").put("fresh", true));
        juno(juno, "set_link", Json.MAPPER.createObjectNode().put("a", "sue").put("b", "jim").put("open", true)
                .put("requested_by_user", false).put("reason", "jim set up the build; sue will need his notes."));
        run(() -> window.board().toggleMaximise("juno"));
        Thread.sleep(800);
        shot("40-juno-cards");
        run(() -> window.board().toggleMaximise("juno"));
        run(() -> window.inbox().open());
        Thread.sleep(700);
        shotAll("41-inbox");
        run(() -> window.inbox().button().fire());

        type(juno, "/status");
        type(juno, "/getcontextfrombob");
        type(juno, "/help");
        run(() -> window.board().toggleMaximise("juno"));
        Thread.sleep(600);
        shot("42-juno-status-context-help");
        run(() -> window.board().toggleMaximise("juno"));
        click("Link");
        Thread.sleep(500);
        type(bob, "/getcontext");
        Thread.sleep(400);
        shot("43-board-links-and-can-talk-to");
        run(() -> window.board().paneOf(juno).toggleOptions());
        Thread.sleep(500);
        shot("44-juno-options");
        run(() -> window.board().paneOf(juno).toggleOptions());

        run(() -> SettingsWindow.show(ctx, stage, SettingsWindow.Page.SYSTEM));
        Thread.sleep(1500);
        shot("45-settings-delegation", SettingsWindow.current());
        run(() -> SettingsWindow.current().close());
        System.out.println("PROPOSALS left " + ctx.runtime.orchestration().proposals().size());
        System.out.println("CONTACTS sue " + ctx.runtime.orchestration().contacts(sue).stream().map(a -> a.name).toList());
        System.out.println("JIM notices " + jim.notices);
    }

    private static void juno(Agent juno, String tool, ObjectNode args) {
        var r = ctx.runtime.call(juno.id, tool, args);
        System.out.println(tool + " → " + r.text());
    }

    // ---------------------------------------------------------------- real CLI

    private static void live() throws Exception {
        click("Cancel");
        Agent juno = ctx.runtime.juno();
        Agent bob = create("bob", "tester");

        // 1. An unnamed task: Juno proposes, the user approves, bob reports back.
        type(juno, "Get one of the agents to write a two-line poem about rain. Don't ask me which; choose one yourself.");
        waitFor(() -> !ctx.runtime.orchestration().proposals().isEmpty() && !juno.turnOpen, 180);
        Thread.sleep(800);
        maxShot(juno, "50-unnamed-proposal");
        System.out.println("PROPOSAL " + ctx.runtime.orchestration().proposals().stream()
                .map(p -> p.kind + " " + p.agentId + " fresh=" + p.fresh + " ask=" + p.askAssign + " why=" + p.why + " task=" + p.task).toList());
        click("Approve", "Start fresh");
        waitFor(() -> bob.task != null && bob.task.reported && !bob.turnOpen, 180);
        waitFor(() -> !juno.turnOpen && juno.status != Agent.Status.WORKING, 180);
        Thread.sleep(2000);
        waitIdle(juno, bob);
        maxShot(bob, "51-bob-did-the-task");
        maxShot(juno, "52-juno-got-the-report");

        // 2. A fresh start always asks.
        String before = bob.conversationId;
        type(juno, "Give bob this task in a fresh conversation (set fresh to true): reply with the single word hello.");
        waitFor(() -> !ctx.runtime.orchestration().proposals().isEmpty() && !juno.turnOpen, 180);
        Thread.sleep(800);
        maxShot(juno, "53-fresh-start-card");
        System.out.println("PROPOSAL " + ctx.runtime.orchestration().proposals().stream()
                .map(p -> p.kind + " fresh=" + p.fresh + " ask=" + p.askAssign + " why=" + p.why).toList());
        click("Start fresh");
        waitIdle(juno, bob);
        System.out.println("FRESH conversation changed=" + !before.equals(bob.conversationId) + " earlier="
                + bob.conversations.size());
        maxShot(bob, "54-bob-fresh");

        // 3. An unlinked message is refused; Juno links bob and jim when asked; then it gets through.
        Agent jim = create("jim", "tester");
        type(bob, "Use the send_message tool to send jim the text: hi jim. If it is refused, tell me exactly what it said.");
        waitIdle(bob, jim);
        maxShot(bob, "55-unlinked-refused");
        type(juno, "Let bob and jim talk to each other.");
        waitFor(() -> ctx.runtime.orchestration().contacts(bob).contains(jim), 180);
        waitIdle(juno);
        shot("56-linked-via-juno");
        type(bob, "Now send jim the text: hi jim, using send_message.");
        waitFor(() -> history(jim).contains("hi jim"), 180);
        waitIdle(bob, jim);
        shot("57-message-delivered");

        // 4. A chain of agent-to-agent messages stops at the hop limit.
        ctx.settings.system.hopLimit = 3;
        type(jim, "Whenever bob sends you a number, reply to bob with send_message with the next number, and nothing "
                + "else. Keep doing it until a message is refused.");
        waitIdle(jim);
        type(bob, "Play a counting game with jim: send jim the number 1 with send_message. Whenever jim sends you a "
                + "number, send jim the next number with send_message. Keep going until a message is refused, then "
                + "tell me what the refusal said.");
        waitFor(() -> bob.hop >= 3 || jim.hop >= 3, 240);
        waitIdle(bob, jim);
        Thread.sleep(2000);
        waitIdle(bob, jim);
        shot("58-hop-limit");
        maxShot(bob, "59-hop-limit-bob");
        maxShot(jim, "60-hop-limit-jim");
        System.out.println("HOPS bob=" + bob.hop + " jim=" + jim.hop + " jim notices=" + jim.notices + " bob notices=" + bob.notices);
        System.out.println("JUNO notices " + juno.notices);
    }

    private static String history(Agent a) {
        StringBuilder sb = new StringBuilder();
        for (var e : ctx.runtime.live(a.id).history()) sb.append(e).append('\n');
        return sb.toString();
    }

    // ---------------------------------------------------------------- helpers

    private static Agent create(String name, String profile) throws Exception {
        Agent[] a = new Agent[1];
        run(() -> a[0] = ctx.runtime.byName(name).orElseGet(() -> ctx.runtime.create(name, profile, null, null)));
        Thread.sleep(400);
        return a[0];
    }

    private static void type(Agent a, String text) throws Exception {
        run(() -> {
            window.board().showAgent(a);
            window.board().paneOf(a).submit(text);
        });
        Thread.sleep(400);
    }

    /** Answers permission requests until the agents are idle (the scenarios shouldn't need any; it's printed). */
    private static void waitIdle(Agent... agents) throws Exception {
        long until = System.currentTimeMillis() + 240_000;
        Thread.sleep(1500);
        while (System.currentTimeMillis() < until) {
            boolean busy = false;
            for (Agent a : agents) {
                LiveAgent l = ctx.runtime.live(a.id);
                if (a.turnOpen || a.status == Agent.Status.QUEUED || !l.pendingPermissions().isEmpty()) busy = true;
                if (!l.pendingPermissions().isEmpty()) {
                    System.out.println("PERMISSION " + a.name + " " + l.pendingPermissions().get(0).tool);
                    run(() -> ctx.runtime.answer(a, null, AgentRuntime.Answer.ALLOW_ONCE, null));
                }
            }
            if (!busy) break;
            Thread.sleep(600);
        }
        Thread.sleep(1000);
    }

    private static void waitFor(BooleanSupplier c, int seconds) throws InterruptedException {
        long until = System.currentTimeMillis() + seconds * 1000L;
        while (!c.getAsBoolean() && System.currentTimeMillis() < until) Thread.sleep(300);
        if (!c.getAsBoolean()) System.out.println("TIMED OUT waiting");
    }

    /** Fires the newest enabled button with the first of the texts found. */
    private static void click(String... texts) throws Exception {
        run(() -> {
            for (String text : texts) {
                Button found = null;
                for (Node n : stage.getScene().getRoot().lookupAll(".button")) {
                    if (n instanceof Button b && b.getText().equals(text) && !b.isDisabled()) found = b;
                }
                if (found != null) {
                    System.out.println("CLICK " + text);
                    found.fire();
                    return;
                }
            }
            System.out.println("no button " + String.join(" / ", texts));
        });
        Thread.sleep(400);
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

    private static void maxShot(Agent a, String name) throws Exception {
        run(() -> window.board().toggleMaximise(a.id));
        Thread.sleep(700);
        shot(name);
        run(() -> window.board().toggleMaximise(a.id));
        Thread.sleep(300);
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
