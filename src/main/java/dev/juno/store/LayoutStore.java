package dev.juno.store;

import dev.juno.model.BoardLayout;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

/** The board layout in {@code ~/.juno/layout.json} (design §7.1). */
public final class LayoutStore {
    private final JunoHome home;

    public LayoutStore(JunoHome home) {
        this.home = home;
    }

    public BoardLayout load() {
        if (!Files.isRegularFile(home.layoutFile())) return new BoardLayout();
        try {
            return BoardLayout.fromJson(Json.MAPPER.readTree(home.layoutFile().toFile()));
        } catch (IOException e) {
            System.err.println("Ignoring unreadable layout " + home.layoutFile() + ": " + e.getMessage());
            return new BoardLayout();
        }
    }

    public void save(BoardLayout layout) {
        try {
            AtomicFiles.write(home.layoutFile(), Json.MAPPER.writeValueAsBytes(layout.toJson()));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save the layout: " + e.getMessage(), e);
        }
    }
}
