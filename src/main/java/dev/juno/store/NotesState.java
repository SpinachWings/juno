package dev.juno.store;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * What the notepad remembers between runs, in {@code ~/.juno/notes.json}: which notes were open (of which notes
 * folder), the view mode and the agent shown beside the notes. Losing it loses nothing but the tabs.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class NotesState {
    public enum Mode { EDIT, SPLIT, PREVIEW }

    /** The notes folder these tabs belong to; tabs are dropped when it changes. */
    public String folder = "";
    public List<String> open = new ArrayList<>();
    public String active = "";
    public Mode mode = Mode.SPLIT;
    /** Agent id shown beside the notes, blank for none. */
    public String agent = "";

    public static NotesState load(JunoHome home) {
        if (!Files.isRegularFile(home.notesStateFile())) return new NotesState();
        try {
            return Json.MAPPER.readValue(home.notesStateFile().toFile(), NotesState.class);
        } catch (IOException e) {
            System.err.println("Ignoring unreadable " + home.notesStateFile() + ": " + e.getMessage());
            return new NotesState();
        }
    }

    public void save(JunoHome home) {
        try {
            AtomicFiles.write(home.notesStateFile(), Json.MAPPER.writeValueAsBytes(this));
        } catch (IOException e) {
            System.err.println("Could not save " + home.notesStateFile() + ": " + e.getMessage());
        }
    }
}
