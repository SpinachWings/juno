package dev.juno.ui;

import dev.juno.app.AppContext;
import dev.juno.app.MainWindow;
import dev.juno.model.Agent;
import dev.juno.model.Project;
import dev.juno.model.PullRequestEntry;
import dev.juno.provider.claude.ClaudeCodeProvider;
import dev.juno.store.JunoHome;
import dev.juno.store.PrStore;
import dev.juno.store.TokenStore;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import javax.imageio.ImageIO;

/**
 * Not a unit test: drives the real window (no Claude Code needed) to look at what Milestone 6b changed: the PRs tab, an
 * agent whose profile was removed, no worktree wording, and the tooltips on shortened text. Prints what it checked.
 * Usage: {@code java -cp ... dev.juno.ui.ChangeScreens <scratch dir> <output dir>}.
 */
public final class ChangeScreens {
    private static Path out;
    private static AppContext ctx;
    private static MainWindow window;
    private static Stage stage;

    public static void main(String[] args) throws Exception {
        Path scratch = Path.of(args[0]);
        out = Path.of(args[1]);
        Files.createDirectories(out);
        Path repo = Files.createDirectories(scratch.resolve("repo"));
        JunoHome home = new JunoHome(scratch.resolve("home"));

        // Two entries from earlier runs, an agent saved with a profile that no longer exists.
        PrStore prs = new PrStore(home.prsFile());
        PullRequestEntry a = new PullRequestEntry();
        a.agentId = "x1";
        a.agentName = "cody";
        a.repo = "acme/api";
        a.number = "#42";
        a.title = "Add login with a very long title so that it has to wrap around the card and shows what happens";
        a.branch = "feature/login";
        a.url = "https://github.com/acme/api/pull/42";
        a.openedAt = System.currentTimeMillis() - 3_600_000;
        prs.add(a);
        PullRequestEntry b = new PullRequestEntry();
        b.agentName = "ann";
        b.repo = "g/p";
        b.number = "!7";
        b.title = "Fix the build";
        b.branch = "fix/build";
        b.url = "https://gitlab.com/g/p/-/merge_requests/7";
        b.openedAt = System.currentTimeMillis() - 60_000;
        prs.add(b);

        TokenStore memory = new TokenStore() {
            public boolean vaultAvailable() { return true; }
            public String vaultProblem() { return ""; }
            public String save(String id, String t) { return TokenStore.keyringReference(id); }
            public void delete(String id) {}
            public Optional<String> resolve(String r) { return Optional.empty(); }
        };
        ctx = new AppContext(home, memory, new ClaudeCodeProvider(scratch.resolve("claude-hooks"), home));
        ctx.skills.refreshBundled(ctx.settings);
        ctx.settings.system.firstRunDone = true;
        Project p = new Project();
        p.id = "p1";
        p.name = "api";
        p.folder = repo.toString();
        ctx.settings.projects.add(p);
        ctx.save();
        ctx.runtime.start();
        Agent cody = ctx.runtime.create("cody", "coder", "p1", null);
        Agent old = ctx.runtime.create("rex", "searcher", "p1", null);
        old.profile = "reviewer";
        ctx.runtime.saved(old);
        ctx.runtime.shutdown();
        ctx = new AppContext(home, memory, new ClaudeCodeProvider(scratch.resolve("claude-hooks"), home));
        ctx.runtime.start();

        Platform.startup(() -> {});
        run(() -> {
            stage = new Stage();
            window = new MainWindow(ctx, stage);
            Scene s = new Scene(window.root(), 1300, 820);
            Theme.apply(s);
            stage.setScene(s);
            stage.show();
        });
        Thread.sleep(1200);
        shot("01-board-rex-needs-a-profile");
        System.out.println("rex: " + ctx.runtime.byName("rex").orElseThrow().statusText());

        toggle("PRs (2)");
        Thread.sleep(400);
        shot("02-prs-tab");
        click("Close");
        Thread.sleep(300);
        shot("03-prs-tab-one-closed");
        System.out.println("entries left after closing one: " + ctx.runtime.pullRequests().size()
                + ", on disk: " + new PrStore(home.prsFile()).list().size());
        toggle("Board");
        Thread.sleep(300);

        // A label cut short with an ellipsis shows its whole text in a tooltip.
        Label[] narrow = new Label[1];
        run(() -> {
            narrow[0] = new Label("this label is much too long for the little room it has been given");
            narrow[0].setMaxWidth(90);
            ((javafx.scene.layout.BorderPane) window.root()).setBottom(narrow[0]);
        });
        Thread.sleep(400);
        run(() -> narrow[0].fireEvent(new MouseEvent(MouseEvent.MOUSE_ENTERED_TARGET, 0, 0, 0, 0,
                javafx.scene.input.MouseButton.NONE, 0, false, false, false, false, false, false, false, false, false,
                false, null)));
        System.out.println("tooltip on the cut label: " + (narrow[0].getTooltip() == null ? null : narrow[0].getTooltip().getText()));
        run(() -> {
            narrow[0].setMaxWidth(900);
            narrow[0].applyCss();
            narrow[0].layout();
        });
        Thread.sleep(300);
        run(() -> narrow[0].fireEvent(new MouseEvent(MouseEvent.MOUSE_ENTERED_TARGET, 0, 0, 0, 0,
                javafx.scene.input.MouseButton.NONE, 0, false, false, false, false, false, false, false, false, false,
                false, null)));
        System.out.println("tooltip once it fits: " + narrow[0].getTooltip());
        shot("04-cut-label");

        ctx.runtime.shutdown();
        Platform.exit();
        System.exit(0);
    }

    private static void click(String text) throws Exception {
        run(() -> {
            Button found = null;
            for (Node n : stage.getScene().getRoot().lookupAll(".button")) {
                if (n instanceof Button b && b.getText().equals(text) && !b.isDisabled()) found = b;
            }
            if (found != null) found.fire();
            else System.out.println("no button " + text);
        });
    }

    private static void toggle(String text) throws Exception {
        run(() -> {
            boolean hit = false;
            for (Node n : stage.getScene().getRoot().lookupAll(".toggle-button")) {
                if (n instanceof ToggleButton b && b.getText().equals(text)) {
                    b.setSelected(true);
                    hit = true;
                }
            }
            if (!hit) System.out.println("no toggle " + text);
        });
    }

    private static void run(Runnable r) throws Exception {
        CountDownLatch l = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                r.run();
            } catch (RuntimeException e) {
                e.printStackTrace();
            } finally {
                l.countDown();
            }
        });
        l.await(30, TimeUnit.SECONDS);
    }

    private static void shot(String name) throws Exception {
        WritableImage[] img = new WritableImage[1];
        run(() -> img[0] = stage.getScene().snapshot(null));
        PixelReader pr = img[0].getPixelReader();
        int width = (int) img[0].getWidth(), height = (int) img[0].getHeight();
        BufferedImage bi = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) bi.setRGB(x, y, pr.getArgb(x, y));
        ImageIO.write(bi, "png", out.resolve(name + ".png").toFile());
        System.out.println("wrote " + name);
    }
}
