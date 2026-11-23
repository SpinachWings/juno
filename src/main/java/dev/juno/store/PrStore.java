package dev.juno.store;

import com.fasterxml.jackson.core.type.TypeReference;
import dev.juno.model.PullRequestEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** {@code ~/.juno/prs.json}: the PRs/MRs agents opened that the user hasn't closed in the PRs tab (design §7.11). */
public final class PrStore {
    private final Path file;
    private final List<PullRequestEntry> entries = new ArrayList<>();

    public PrStore(Path file) {
        this.file = file;
        if (Files.isRegularFile(file)) {
            try {
                entries.addAll(Json.MAPPER.readValue(file.toFile(), new TypeReference<List<PullRequestEntry>>() {}));
            } catch (IOException e) {
                System.err.println("Couldn't read " + file + ", starting with an empty PRs tab: " + e.getMessage());
            }
        }
    }

    /** Newest first. */
    public synchronized List<PullRequestEntry> list() {
        List<PullRequestEntry> out = new ArrayList<>(entries);
        out.sort((a, b) -> Long.compare(b.openedAt, a.openedAt));
        return out;
    }

    public synchronized void add(PullRequestEntry e) {
        entries.add(e);
        save();
    }

    public synchronized void remove(String id) {
        if (entries.removeIf(e -> e.id.equals(id))) save();
    }

    private void save() {
        try {
            AtomicFiles.write(file, Json.MAPPER.writeValueAsBytes(entries));
        } catch (IOException e) {
            System.err.println("Could not save " + file + ": " + e.getMessage());
        }
    }
}
