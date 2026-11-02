package dev.juno.ui;

import dev.juno.app.AppContext;
import dev.juno.app.MainWindow;
import dev.juno.model.Agent;
import dev.juno.model.Project;
import dev.juno.provider.claude.ClaudeCodeProvider;
import dev.juno.store.JunoHome;
import dev.juno.store.NotesFolder;
import dev.juno.store.TokenStore;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.fxmisc.richtext.StyleClassedTextArea;

/**
 * Not a unit test: drives the real window against a scratch Juno home and a scratch notes folder (no Claude Code
 * needed), writing a screenshot after each step and printing what it checked.
 * Usage: {@code java -cp ... dev.juno.ui.NotesScreens <scratch dir> <output dir>}.
 */
public final class NotesScreens {
    private static Path out;
    private static AppContext ctx;
    private static MainWindow window;
    private static Stage stage;
    private static Path notes;
    private static Path repo;

    public static void main(String[] args) throws Exception {
        Path scratch = Path.of(args[0]);
        out = Path.of(args[1]);
        Files.createDirectories(out);
        notes = Files.createDirectories(scratch.resolve("my-notes"));
        repo = Files.createDirectories(scratch.resolve("repo"));
        Files.createDirectories(notes.resolve("projects"));
        Files.createDirectories(notes.resolve("daily"));
        Files.writeString(notes.resolve("ideas.md"), String.join("\n",
                "# Ideas", "", "A list of things to try, with **bold** and `code`:", "",
                "- first thing", "- second thing", "", "> A quote worth keeping.", "",
                "```java", "int x = 42; // a code block", "```", "",
                "| name | use |", "| ---- | --- |", "| juno | agents |", ""));
        Files.writeString(notes.resolve("todo.txt"), "plain text\nsecond line\n");
        Files.writeString(notes.resolve("projects/api-plan.md"), "# API plan\n\nShip it.\n");
        Files.writeString(notes.resolve("daily/2026-11-02.md"), "# Monday\n");
        Files.writeString(notes.resolve("photo.png"), "not shown");

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
        Project p = new Project();
        p.id = "p1";
        p.name = "greeter";
        p.folder = repo.toString();
        ctx.settings.projects.add(p);
        ctx.save();
        ctx.runtime.start();

        Platform.startup(() -> {});
        run(() -> {
            stage = new Stage();
            window = new MainWindow(ctx, stage);
            Scene s = new Scene(window.root(), 1400, 900);
            Theme.apply(s);
            stage.setScene(s);
            stage.show();
        });
        Thread.sleep(1000);
        // With no agents the board opens a setup cell; cancel it so Juno is alone.
        click("Cancel");
        Thread.sleep(300);

        shot("01-board");
        toggle("Notes");
        Thread.sleep(400);
        shot("02-no-notes-folder");

        run(() -> {
            ctx.settings.system.notesFolder = notes.toString();
            ctx.save();
        });
        Thread.sleep(500);
        shot("03-tree");

        select("ideas.md");
        Thread.sleep(600);
        shot("04-open-note-split");

        // Typing changes the preview, and is saved by itself.
        run(() -> editor().appendText("\n## Added while typing\n\nSaved without asking.\n"));
        Thread.sleep(1600);
        String disk = Files.readString(notes.resolve("ideas.md"));
        System.out.println("autosave: " + disk.contains("Saved without asking."));
        shot("05-typed-and-saved");

        select("todo.txt");
        Thread.sleep(400);
        select("projects/api-plan.md");
        Thread.sleep(400);
        shot("06-three-tabs");

        // Agent beside the notes: the board's own Juno pane.
        run(() -> {
            @SuppressWarnings("unchecked")
            ComboBox<Agent> c = (ComboBox<Agent>) window.root().lookup(".combo-box");
            c.getSelectionModel().select(ctx.runtime.juno());
        });
        Thread.sleep(800);
        boolean[] inNotes = new boolean[1];
        run(() -> inNotes[0] = isInside(window.board().paneOf(ctx.runtime.juno()).root(), window.root().getCenter()));
        System.out.println("juno pane is inside the notes view: " + inNotes[0]);
        shot("07-agent-beside");

        // Type into it from the notes view: the same pane.
        run(() -> window.board().paneOf(ctx.runtime.juno()).submit("/help"));
        Thread.sleep(500);
        shot("08-agent-beside-help");

        toggle("Edit");
        Thread.sleep(300);
        shot("09-edit-only");
        toggle("Preview");
        Thread.sleep(300);
        shot("10-preview-only");
        toggle("Split");

        // Back to the board: the pane returns to its cell, and the notes are saved.
        toggle("Board");
        Thread.sleep(600);
        boolean[] back = new boolean[1];
        run(() -> back[0] = isInside(window.board().paneOf(ctx.runtime.juno()).root(), window.board().root()));
        System.out.println("juno pane is back on the board: " + back[0]);
        shot("11-board-again");

        // Back to the notes: tabs and the agent beside come back.
        toggle("Notes");
        Thread.sleep(700);
        shot("12-notes-again");

        // A clean note changed on disk follows the disk.
        Files.writeString(notes.resolve("todo.txt"), "changed elsewhere\n");
        Files.setLastModifiedTime(notes.resolve("todo.txt"), FileTime.fromMillis(System.currentTimeMillis() + 5000));
        click("⟳");
        Thread.sleep(400);
        select("todo.txt");
        Thread.sleep(300);
        shot("13-reloaded-from-disk");
        System.out.println("reloaded: " + editor().getText().contains("changed elsewhere"));

        // A note being edited, changed on disk: asks.
        run(() -> editor().appendText("my edit\n"));
        Files.writeString(notes.resolve("todo.txt"), "changed elsewhere again\n");
        Files.setLastModifiedTime(notes.resolve("todo.txt"), FileTime.fromMillis(System.currentTimeMillis() + 9000));
        Thread.sleep(1500);
        shot("14-conflict");
        System.out.println("conflict left the disk alone: " + Files.readString(notes.resolve("todo.txt")).contains("again"));

        // A notes folder inside a project is refused in the view.
        toggle("Board");
        run(() -> {
            ctx.settings.system.notesFolder = repo.resolve("notes").toString();
            ctx.save();
        });
        toggle("Notes");
        Thread.sleep(600);
        shot("15-overlap");

        ctx.runtime.shutdown();
        Platform.exit();
        System.exit(0);
    }

    private static boolean isInside(Node n, Node ancestor) {
        for (Node x = n; x != null; x = x.getParent()) if (x == ancestor) return true;
        return false;
    }

    private static StyleClassedTextArea editor() {
        StyleClassedTextArea found = null;
        for (Node n : window.root().lookupAll(".editor")) {
            if (n instanceof StyleClassedTextArea a && showing(a)) found = a;
        }
        return found;
    }

    private static boolean showing(Node n) {
        for (Node x = n; x != null; x = x.getParent()) if (!x.isVisible()) return false;
        return n.getScene() != null;
    }

    @SuppressWarnings("unchecked")
    private static void select(String path) throws Exception {
        run(() -> {
            TreeView<NotesFolder.Entry> tv = (TreeView<NotesFolder.Entry>) window.root().lookup(".notes-tree");
            TreeItem<NotesFolder.Entry> it = find(tv.getRoot(), path);
            for (var q = it.getParent(); q != null; q = q.getParent()) q.setExpanded(true);
            tv.getSelectionModel().select(it);
            tv.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
        });
    }

    private static TreeItem<NotesFolder.Entry> find(TreeItem<NotesFolder.Entry> it, String path) {
        if (it.getValue() != null && it.getValue().path().equals(path)) return it;
        for (var c : it.getChildren()) {
            var r = find(c, path);
            if (r != null) return r;
        }
        return null;
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
            for (Node n : stage.getScene().getRoot().lookupAll(".toggle-button"))
                if (n instanceof ToggleButton b && b.getText().equals(text)) b.setSelected(true);
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
