package dev.juno.store;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * What the Folders view remembers between runs, in {@code ~/.juno/folders.json}: the files that were open, the folders
 * that were collapsed, the editor's text size and the agent shown beside the files. Losing it loses nothing but the tabs.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FoldersState {
    public static final double DEFAULT_SIZE = 13;
    public static final double MIN_SIZE = 8;
    public static final double MAX_SIZE = 40;

    /** Absolute paths of the open files, in tab order. */
    public List<String> open = new ArrayList<>();
    public String active = "";
    /** Absolute paths of the listed folders that are collapsed. */
    public List<String> collapsed = new ArrayList<>();
    /** Editor text size in points (Ctrl + / Ctrl -). */
    public double size = DEFAULT_SIZE;
    /** Agent id shown beside the files, blank for none. */
    public String agent = "";

    public static FoldersState load(JunoHome home) {
        if (!Files.isRegularFile(home.foldersStateFile())) return new FoldersState();
        try {
            return Json.MAPPER.readValue(home.foldersStateFile().toFile(), FoldersState.class);
        } catch (IOException e) {
            System.err.println("Ignoring unreadable " + home.foldersStateFile() + ": " + e.getMessage());
            return new FoldersState();
        }
    }

    public void save(JunoHome home) {
        try {
            AtomicFiles.write(home.foldersStateFile(), Json.MAPPER.writeValueAsBytes(this));
        } catch (IOException e) {
            System.err.println("Could not save " + home.foldersStateFile() + ": " + e.getMessage());
        }
    }
}
