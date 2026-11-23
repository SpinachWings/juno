package dev.juno.comms;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.juno.store.AtomicFiles;
import dev.juno.store.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** {@code ~/.juno/comms.json}: open links and proposals held for approval, saved after every change (design §4.1). */
public final class CommsStore {
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Data {
        public List<Links.Link> links = new ArrayList<>();
        public List<Proposal> proposals = new ArrayList<>();
    }

    private final Path file;
    private final Data data;
    private final Links links;

    public CommsStore(Path file) {
        this.file = file;
        Data d = new Data();
        if (Files.isRegularFile(file)) {
            try {
                d = Json.MAPPER.readValue(file.toFile(), Data.class);
            } catch (IOException e) {
                System.err.println("Couldn't read " + file + ", starting without links: " + e.getMessage());
            }
        }
        this.data = d;
        this.links = new Links(data.links);
    }

    public Links links() {
        return links;
    }

    /** The live list (oldest first); call {@link #save()} after changing it. */
    public List<Proposal> proposals() {
        return data.proposals;
    }

    public void save() {
        try {
            AtomicFiles.write(file, Json.MAPPER.writeValueAsBytes(data));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save " + file + ": " + e.getMessage(), e);
        }
    }
}
