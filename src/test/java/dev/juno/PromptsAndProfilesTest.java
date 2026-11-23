package dev.juno;

import static org.junit.jupiter.api.Assertions.*;

import dev.juno.model.BuiltInProfiles;
import dev.juno.model.Profile;
import dev.juno.prompts.Prompts;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PromptsAndProfilesTest {
    private static final Map<String, Object> VALUES = Map.of(
            "agent_name", "bob", "profile_name", "coder", "fresh_start_threshold", 60,
            "build_and_test_commands", "npm test", "args", "", "git_repo", true, "branch", "main");

    @Test
    void fourBuiltInProfilesWithTheirPrompts() {
        List<Profile> all = BuiltInProfiles.create();
        assertEquals(List.of("orchestrator", "coder", "planner", "searcher"),
                all.stream().map(p -> p.name).toList());
        for (Profile p : all) {
            assertTrue(p.builtIn);
            assertFalse(p.systemPrompt.isBlank(), p.name);
            // Every placeholder in a system prompt is one the app supplies.
            assertDoesNotThrow(() -> Prompts.render(p.systemPrompt, VALUES), p.name);
        }
    }

    @Test
    void coderHasLockedCommentAndConflictCommands() {
        Profile coder = BuiltInProfiles.create().get(1);
        assertTrue(coder.editsFiles && coder.needsProjectFolder);
        assertEquals(List.of("checkforcomments", "checkformergeconflicts"), coder.commands.stream().map(c -> c.name).toList());
        for (var c : coder.commands) {
            assertTrue(c.builtIn);
            assertDoesNotThrow(() -> Prompts.render(c.template, VALUES));
        }
        assertTrue(coder.disallowedTools.contains("Bash(gh:*)"));
        assertTrue(coder.allowedTools.containsAll(List.of("mcp__juno__hand_over", "host:read", "host:open", "host:reply")));
        assertTrue(coder.allowedTools.stream().noneMatch(t -> t.equals("host:*") || t.equals("host:merge")));
    }

    @Test
    void thePlannerCanReadIssuesButNothingElseOnTheHost() {
        Profile planner = BuiltInProfiles.create().get(2);
        assertEquals("planner", planner.name);
        assertTrue(planner.allowedTools.contains("host:read"));
        assertFalse(planner.allowedTools.contains("host:open") || planner.allowedTools.contains("host:reply"));
        assertTrue(planner.disallowedTools.containsAll(List.of("Edit", "Write")));
    }

    @Test
    void theCoderPromptCoversBranchesCoordinationHandOverAndIssues() {
        String p = BuiltInProfiles.create().get(1).systemPrompt;
        for (String must : List.of("git init", "existing branch", "new branch", "using-git-worktrees", "hand_over",
                "/checkformergeconflicts", "issue")) {
            assertTrue(p.contains(must), must);
        }
        assertFalse(p.toLowerCase().contains("reviewer"));
    }

    @Test
    void renderSectionsAndUnknownPlaceholders() {
        assertEquals("hi bob!", Prompts.render("hi {{n}}{{#x}}!{{/x}}{{^x}}?{{/x}}", Map.of("n", "bob", "x", "yes")));
        assertEquals("hi bob?", Prompts.render("hi {{n}}{{#x}}!{{/x}}{{^x}}?{{/x}}", Map.of("n", "bob", "x", "")));
        assertThrows(IllegalArgumentException.class, () -> Prompts.render("{{nope}}", Map.of()));
    }

    @Test
    void keyedFilesSplitIntoSections() {
        Map<String, String> s = Prompts.sections("# Title\nnotes\n## a\nfirst\n## b\nsecond\nline");
        assertEquals(List.of("a", "b"), List.copyOf(s.keySet()));
        assertEquals("second\nline", s.get("b"));
    }
}
