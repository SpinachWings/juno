package dev.juno.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * One folder of the Folders view (design §9): its files and sub-folders on disk. Paths are given relative to the folder
 * with {@code /} separators ({@code ""} is the folder itself); nothing outside it can be reached. Writes are atomic.
 * Refusals are {@link FolderException}s whose message is fit to show.
 */
public final class FolderTree {
    /** Added to a new file's name when it has no extension. */
    public static final String DEFAULT_EXTENSION = "md";
    /** Larger files are not opened in the editor. */
    public static final long MAX_EDIT_BYTES = 2_000_000;

    /** A refusal, worded for the user. */
    public static final class FolderException extends RuntimeException {
        public FolderException(String message) {
            super(message);
        }
    }

    /** One entry of the tree. {@code path} is relative to the folder. */
    public record Entry(String path, String name, boolean folder) {}

    private final Path root;

    public FolderTree(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public boolean exists() {
        return Files.isDirectory(root);
    }

    // ---------------------------------------------------------------- reading

    /**
     * The entries directly inside {@code rel}: folders first, then files, each by name ignoring case. Entries whose
     * name starts with a dot ({@code .git}, {@code .obsidian}) are left out. The view lists a folder when it is opened.
     */
    public List<Entry> list(String rel) {
        Path dir = resolveFolder(rel);
        List<Entry> entries = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir)) {
            for (Path p : s) {
                String name = p.getFileName().toString();
                if (name.startsWith(".")) continue;
                String childRel = rel.isEmpty() ? name : rel + "/" + name;
                if (Files.isDirectory(p)) entries.add(new Entry(childRel, name, true));
                else if (Files.isRegularFile(p)) entries.add(new Entry(childRel, name, false));
            }
        } catch (IOException e) {
            return List.of();
        }
        entries.sort(Comparator.comparing(Entry::folder).reversed().thenComparing(e -> e.name().toLowerCase(Locale.ROOT)));
        return entries;
    }

    public String read(String rel) {
        Path p = resolve(rel);
        try {
            if (Files.size(p) > MAX_EDIT_BYTES) throw new FolderException(rel + " is too large to edit here.");
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new FolderException(rel + " isn't a UTF-8 text file.");
        } catch (IOException e) {
            throw new FolderException("Couldn't read " + rel + ": " + e.getMessage());
        }
    }

    public long modified(String rel) {
        try {
            return Files.getLastModifiedTime(resolve(rel)).toMillis();
        } catch (IOException e) {
            return -1;
        }
    }

    public boolean isFolder(String rel) {
        return Files.isDirectory(resolve(rel));
    }

    public boolean isFile(String rel) {
        return Files.isRegularFile(resolve(rel));
    }

    // ---------------------------------------------------------------- writing

    public void write(String rel, String text) {
        Path p = resolve(rel);
        try {
            AtomicFiles.writeString(p, text);
        } catch (IOException e) {
            throw new FolderException("Couldn't save " + rel + ": " + e.getMessage());
        }
    }

    /** A new, empty file in {@code parent}; {@code .md} is added when the name has no extension. Returns its path. */
    public String createFile(String parent, String name) {
        String file = cleanName(name);
        if (!hasExtension(file)) file = file + "." + DEFAULT_EXTENSION;
        Path dir = resolveFolder(parent);
        Path target = child(dir, file);
        if (Files.exists(target)) throw new FolderException("There is already something called " + file + " there.");
        try {
            AtomicFiles.writeString(target, "");
        } catch (IOException e) {
            throw new FolderException("Couldn't create " + file + ": " + e.getMessage());
        }
        return relative(target);
    }

    public String createFolder(String parent, String name) {
        String folder = cleanName(name);
        Path target = child(resolveFolder(parent), folder);
        if (Files.exists(target)) throw new FolderException("There is already something called " + folder + " there.");
        try {
            Files.createDirectory(target);
        } catch (IOException e) {
            throw new FolderException("Couldn't create " + folder + ": " + e.getMessage());
        }
        return relative(target);
    }

    /** Renames a file or folder in place. A file keeps its extension when the new name has none. Returns the new path. */
    public String rename(String rel, String newName) {
        Path from = resolveExisting(rel);
        String name = cleanName(newName);
        String old = from.getFileName().toString();
        if (Files.isRegularFile(from) && !hasExtension(name) && hasExtension(old)) name = name + old.substring(old.lastIndexOf('.'));
        Path to = child(from.getParent(), name);
        if (to.equals(from)) return rel;
        return moveTo(from, to);
    }

    /** Moves a file or folder into {@code newParent}. Returns the new path. */
    public String move(String rel, String newParent) {
        Path from = resolveExisting(rel);
        Path dir = resolveFolder(newParent);
        if (Files.isDirectory(from) && (dir.equals(from) || dir.startsWith(from)))
            throw new FolderException("A folder can't be moved into itself.");
        Path to = child(dir, from.getFileName().toString());
        if (to.equals(from)) return rel;
        return moveTo(from, to);
    }

    private String moveTo(Path from, Path to) {
        if (Files.exists(to)) throw new FolderException("There is already something called " + to.getFileName() + " there.");
        try {
            try {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(from, to);
            }
        } catch (IOException e) {
            throw new FolderException("Couldn't move " + from.getFileName() + ": " + e.getMessage());
        }
        return relative(to);
    }

    /** Deletes a file, or a folder with everything in it. The caller confirms first. */
    public void delete(String rel) {
        Path p = resolveExisting(rel);
        if (p.equals(root)) throw new FolderException("The folder itself can't be deleted from here; remove it from the list instead.");
        try (Stream<Path> walk = Files.walk(p)) {
            for (Path x : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(x);
        } catch (IOException e) {
            throw new FolderException("Couldn't delete " + rel + ": " + e.getMessage());
        }
    }

    /** How many files and folders a delete of {@code rel} would remove (for the confirmation). */
    public int countBelow(String rel) {
        Path p = resolveExisting(rel);
        if (!Files.isDirectory(p)) return 0;
        try (Stream<Path> walk = Files.walk(p)) {
            return (int) walk.count() - 1;
        } catch (IOException e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- paths

    /** The absolute path of a relative one, refusing anything that leaves the folder. */
    public Path resolve(String rel) {
        if (rel == null) throw new FolderException("No file given.");
        String r = rel.replace('\\', '/');
        if (r.startsWith("/") || r.matches("^[A-Za-z]:.*")) throw new FolderException("That path isn't inside the folder.");
        Path p = root.resolve(r).normalize();
        if (!p.startsWith(root)) throw new FolderException("That path isn't inside the folder.");
        if (Files.exists(p)) {
            try {
                if (!p.toRealPath().startsWith(root.toRealPath())) throw new FolderException("That path isn't inside the folder.");
            } catch (IOException e) {
                throw new FolderException("Couldn't open " + rel + ": " + e.getMessage());
            }
        }
        return p;
    }

    private Path resolveExisting(String rel) {
        Path p = resolve(rel);
        if (!Files.exists(p)) throw new FolderException(rel + " no longer exists.");
        return p;
    }

    private Path resolveFolder(String rel) {
        Path p = resolve(rel == null ? "" : rel);
        if (!Files.isDirectory(p)) throw new FolderException("There is no folder " + (rel == null || rel.isEmpty() ? "here" : rel) + ".");
        return p;
    }

    private Path child(Path dir, String name) {
        Path p = dir.resolve(name).normalize();
        if (!p.getParent().equals(dir.normalize())) throw new FolderException("A name can't contain a slash.");
        return p;
    }

    private String relative(Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }

    private static boolean hasExtension(String fileName) {
        return fileName.lastIndexOf('.') > 0;
    }

    private static String cleanName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) throw new FolderException("Give it a name.");
        if (n.contains("/") || n.contains("\\")) throw new FolderException("A name can't contain a slash.");
        if (n.equals(".") || n.equals("..")) throw new FolderException("That isn't a usable name.");
        if (n.startsWith(".")) throw new FolderException("Names starting with a dot are hidden; pick another.");
        if (n.matches(".*[<>:\"|?*].*")) throw new FolderException("A name can't contain any of < > : \" | ? *");
        if (n.endsWith(".") || n.endsWith(" ")) throw new FolderException("A name can't end with a dot or a space.");
        return n;
    }
}
