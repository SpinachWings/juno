package dev.juno.ui;

import java.util.ArrayList;
import java.util.List;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;

/** Juno's icon: a ringed planet. It marks the toolbar, every window, and the taskbar or dock entry. */
public final class Icons {
    private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};
    private static List<Image> all;

    private Icons() {}

    /** The icon in every size, for {@link Stage#getIcons()}; the platform picks the one it needs. */
    public static synchronized List<Image> all() {
        if (all == null) {
            all = new ArrayList<>();
            for (int size : SIZES) {
                Image img = new Image(Icons.class.getResource("/dev/juno/ui/icons/icon-" + size + ".png").toExternalForm());
                all.add(img);
            }
        }
        return all;
    }

    /** The icon as a small image for the toolbar. */
    public static ImageView logo(double size) {
        ImageView v = new ImageView(all().get(all().size() - 1));
        v.setFitWidth(size);
        v.setFitHeight(size);
        v.setSmooth(true);
        return v;
    }

    /** Gives every window the scene is shown in the icon (title bar, Alt-Tab, taskbar). */
    public static void forWindows(Scene scene) {
        scene.windowProperty().addListener((o, a, w) -> {
            if (w instanceof Stage s && s.getIcons().isEmpty()) s.getIcons().addAll(all());
        });
    }

    /** The taskbar or dock icon of the running process, where the platform lets Java set it (macOS dock, some Linux). */
    public static void taskbar() {
        try {
            if (java.awt.Taskbar.isTaskbarSupported()) {
                java.awt.Taskbar tb = java.awt.Taskbar.getTaskbar();
                if (tb.isSupported(java.awt.Taskbar.Feature.ICON_IMAGE)) {
                    tb.setIconImage(javax.imageio.ImageIO.read(Icons.class.getResource("/dev/juno/ui/icons/icon-256.png")));
                }
            }
        } catch (Exception | LinkageError e) {
            // No taskbar API here (headless, or a platform without it): the window icon still applies.
        }
    }
}
