package dev.juno.ui;

import dev.juno.app.AppContext;
import dev.juno.app.MainWindow;
import dev.juno.model.Agent;
import dev.juno.model.Project;
import dev.juno.provider.claude.ClaudeCodeProvider;
import dev.juno.store.JunoHome;
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
 * Not a unit test: drives the real window against a scratch Juno home and two scratch folders (no Claude Code
 * needed), writing a screenshot after each step and printing what it checked.
 * Usage: {@code java -cp ... dev.juno.ui.FoldersScreens <scratch dir> <output dir>}.
 */
public final class FoldersScreens {
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
                "```java", "int x = 42; // a code block", "```", ""));
        Files.writeString(notes.resolve("todo.txt"), "plain text\nsecond line\n");
        Files.writeString(notes.resolve("projects/api-plan.md"), "# API plan\n\nShip it.\n");
        Files.writeString(notes.resolve("daily/2026-11-02.md"), "# Monday\n");
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src/Main.java"), "class Main {\n    public static void main(String[] a) { }\n}\n");
        Files.writeString(repo.resolve("README.txt"), "a project folder inside the list\n");

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
        System.out.println("agents toggle shown on the board: " + toggleShown("Agents"));
        toggle("Folders");
        Thread.sleep(400);
        System.out.println("agents toggle shown on folders: " + toggleShown("Agents"));
        shot("02-no-folders");

        // A project folder inside a listed folder is fine now: the project folder is listed too.
        run(() -> {
            ctx.settings.folders.add(notes.toString());
            ctx.settings.folders.add(repo.toString());
            ctx.save();
        });
        Thread.sleep(500);
        shot("03-two-folders");

        select(notes.resolve("ideas.md"));
        Thread.sleep(600);
        shot("04-open-file");

        // Typing is saved by itself.
        run(() -> editor().appendText("\n## Added while typing\n\nSaved without asking.\n"));
        Thread.sleep(1600);
        String disk = Files.readString(notes.resolve("ideas.md"));
        System.out.println("autosave: " + disk.contains("Saved without asking."));

        // Any text file opens: a source file of the second folder and a plain one of the first.
        select(repo.resolve("src/Main.java"));
        Thread.sleep(400);
        select(notes.resolve("todo.txt"));
        Thread.sleep(400);
        shot("05-three-tabs");

        // Ctrl + and Ctrl - change the text size; Ctrl 0 resets it.
        double before = size();
        key(KeyCode.EQUALS);
        key(KeyCode.EQUALS);
        Thread.sleep(200);
        double bigger = size();
        key(KeyCode.MINUS);
        Thread.sleep(200);
        System.out.println("zoom: " + before + " -> " + bigger + " -> " + size());
        shot("06-bigger-text");
        key(KeyCode.DIGIT0);
        Thread.sleep(200);
        System.out.println("zoom reset: " + size());

        // Reordering the list: the selected folder moves down one.
        run(() -> tree().getSelectionModel().select(rootItem(0)));
        click("↓");
        Thread.sleep(500);
        System.out.println("order after down: " + ctx.settings.folders.stream().map(x -> Path.of(x).getFileName().toString()).toList());
        shot("07-reordered");

        // Collapse the first folder in the list.
        run(() -> rootItem(0).setExpanded(false));
        Thread.sleep(300);
        shot("08-collapsed");

        // Agent beside the files: the board's own Juno pane.
        run(() -> {
            @SuppressWarnings("unchecked")
            ComboBox<Agent> c = (ComboBox<Agent>) window.root().lookup(".combo-box");
            c.getSelectionModel().select(ctx.runtime.juno());
        });
        Thread.sleep(800);
        boolean[] inView = new boolean[1];
        run(() -> inView[0] = isInside(window.board().paneOf(ctx.runtime.juno()).root(), window.root().getCenter()));
        System.out.println("juno pane is inside the folders view: " + inView[0]);
        shot("09-agent-beside");

        // Back to the board: the pane returns to its cell, and the files are saved.
        toggle("Board");
        Thread.sleep(600);
        boolean[] back = new boolean[1];
        run(() -> back[0] = isInside(window.board().paneOf(ctx.runtime.juno()).root(), window.board().root()));
        System.out.println("juno pane is back on the board: " + back[0]);
        shot("10-board-again");

        // Back to the folders: tabs, collapsed state and the agent beside come back.
        toggle("Folders");
        Thread.sleep(700);
        boolean[] collapsed = new boolean[1];
        run(() -> collapsed[0] = !rootItem(0).isExpanded());
        System.out.println("first folder still collapsed: " + collapsed[0]);
        shot("11-folders-again");

        // A clean file changed on disk follows the disk.
        Files.writeString(notes.resolve("todo.txt"), "changed elsewhere\n");
        Files.setLastModifiedTime(notes.resolve("todo.txt"), FileTime.fromMillis(System.currentTimeMillis() + 5000));
        click("⟳");
        Thread.sleep(400);
        select(notes.resolve("todo.txt"));
        Thread.sleep(300);
        shot("12-reloaded-from-disk");
        System.out.println("reloaded: " + editor().getText().contains("changed elsewhere"));

        // A file being edited, changed on disk: asks.
        run(() -> editor().appendText("my edit\n"));
        Files.writeString(notes.resolve("todo.txt"), "changed elsewhere again\n");
        Files.setLastModifiedTime(notes.resolve("todo.txt"), FileTime.fromMillis(System.currentTimeMillis() + 9000));
        Thread.sleep(1500);
        shot("13-conflict");
        System.out.println("conflict left the disk alone: " + Files.readString(notes.resolve("todo.txt")).contains("again"));

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

    private static double size() throws Exception {
        double[] v = new double[1];
        run(() -> v[0] = fontPoints(editor()));
        return v[0];
    }

    /** The editor's text size in points, read from its inline style (-fx-font-size: Npt). */
    private static double fontPoints(Node editor) {
        String style = editor.getStyle();
        if (style == null || !style.contains("-fx-font-size:")) return 13;
        String n = style.substring(style.indexOf(':') + 1, style.indexOf("pt")).trim();
        return Double.parseDouble(n);
    }

    private static boolean showing(Node n) {
        for (Node x = n; x != null; x = x.getParent()) if (!x.isVisible()) return false;
        return n.getScene() != null;
    }

    @SuppressWarnings("unchecked")
    private static TreeView<Object> tree() {
        return (TreeView<Object>) window.root().lookup(".folders-tree");
    }

    private static TreeItem<Object> rootItem(int i) {
        return tree().getRoot().getChildren().get(i);
    }

    /** The absolute path of a row; rows are private records, so it is read by reflection. */
    private static Path absolute(TreeItem<Object> row) {
        try {
            Object v = row.getValue();
            var m = v.getClass().getDeclaredMethod("absolute");
            m.setAccessible(true);
            return (Path) m.invoke(v);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Selects the row of an absolute path (in any listed folder) and opens it, like Enter. */
    private static void select(Path path) throws Exception {
        run(() -> {
            TreeView<Object> tv = tree();
            TreeItem<Object> it = find(tv.getRoot(), path);
            if (it == null) {
                System.out.println("no row for " + path);
                return;
            }
            for (var q = it.getParent(); q != null; q = q.getParent()) q.setExpanded(true);
            tv.getSelectionModel().select(it);
            tv.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
        });
    }

    private static TreeItem<Object> find(TreeItem<Object> it, Path path) {
        for (TreeItem<Object> c : it.getChildren()) {
            Path here = c.getValue() == null ? null : absolute(c);
            if (here == null) continue;
            if (here.equals(path)) return c;
            if (path.startsWith(here) && !c.isLeaf()) {
                TreeItem<Object> r = find(c, path);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** A Ctrl + key press in the editor, as the view's filter sees it. */
    private static void key(KeyCode code) throws Exception {
        run(() -> editor().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, true, false, false)));
    }

    private static boolean toggleShown(String text) throws Exception {
        boolean[] shown = new boolean[1];
        run(() -> {
            for (Node n : stage.getScene().getRoot().lookupAll(".toggle-button"))
                if (n instanceof ToggleButton b && b.getText().equals(text)) shown[0] = b.isVisible() && b.isManaged();
        });
        return shown[0];
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
