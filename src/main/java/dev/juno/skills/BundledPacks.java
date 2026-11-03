package dev.juno.skills;

import dev.juno.model.BuiltInProfiles;
import java.util.List;
import java.util.Optional;

/** Packs shipped inside Juno, and their locked default assignments (design §5.5). */
public final class BundledPacks {
    public record Bundled(String id, String name, String entrySkill) {}

    public static final List<Bundled> ALL = List.of(new Bundled("superpowers", "superpowers", "using-superpowers"));

    private BundledPacks() {}

    public static boolean isBundledId(String id) {
        return ALL.stream().anyMatch(b -> b.id().equals(id));
    }

    /**
     * The locked skills of a bundled pack for a built-in profile, or empty if the pack isn't locked-assigned to it.
     * coder: every skill, including {@code using-git-worktrees} (Juno makes no worktrees; the coder does, design §7.8).
     * planner: only {@code using-superpowers}, {@code brainstorming}, {@code writing-plans}.
     */
    public static Optional<List<String>> locked(String packId, String profile, List<String> allSkills) {
        if (!packId.equals("superpowers")) return Optional.empty();
        if (profile.equals(BuiltInProfiles.CODER))
            return Optional.of(List.copyOf(allSkills));
        if (profile.equals(BuiltInProfiles.PLANNER))
            return Optional.of(List.of("using-superpowers", "brainstorming", "writing-plans").stream()
                    .filter(allSkills::contains).toList());
        return Optional.empty();
    }

    /** Profiles with a locked assignment for the pack. */
    public static List<String> lockedProfiles(String packId) {
        return packId.equals("superpowers")
                ? List.of(BuiltInProfiles.CODER, BuiltInProfiles.PLANNER) : List.of();
    }
}
