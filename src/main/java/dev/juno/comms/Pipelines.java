package dev.juno.comms;

import dev.juno.comms.HostApi.Check;
import dev.juno.comms.HostApi.CheckState;
import dev.juno.comms.HostApi.Checks;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Decides what a head commit's checks mean for a review (design §6.3). Passed = every required check passed where the
 * host marks some required, otherwise every check; failing optional checks don't block. Needs a person comes first:
 * nothing else moves until someone acts.
 */
public final class Pipelines {
    public enum Result { PASSED, NONE, NOT_STARTED, PENDING, FAILED, NEEDS_PERSON }

    /** {@code names}: the failed jobs, or what waits for a person. {@code optionalFailed}: goes in the summary. */
    public record Outcome(Result result, List<String> names, List<String> optionalFailed, String summary) {}

    private Pipelines() {}

    public static Outcome evaluate(Checks c) {
        if (c.checks().isEmpty() && c.missingRequired().isEmpty()) {
            return new Outcome(c.configured() ? Result.NOT_STARTED : Result.NONE, List.of(), List.of(), "none");
        }
        List<String> person = names(c.checks(), CheckState.NEEDS_PERSON, x -> true);
        if (!person.isEmpty()) return new Outcome(Result.NEEDS_PERSON, person, List.of(), "");
        boolean requiredKnown = !c.missingRequired().isEmpty() || c.checks().stream().anyMatch(Check::required);
        List<Check> counted = requiredKnown ? c.checks().stream().filter(Check::required).toList() : c.checks();
        List<String> failed = names(counted, CheckState.FAILED, x -> true);
        if (!failed.isEmpty()) return new Outcome(Result.FAILED, failed, List.of(), "");
        boolean pending = !c.missingRequired().isEmpty()
                || counted.stream().anyMatch(x -> x.state() == CheckState.PENDING);
        if (pending) return new Outcome(Result.PENDING, List.of(), List.of(), "");
        List<String> optional = requiredKnown ? names(c.checks(), CheckState.FAILED, x -> !x.required()) : List.of();
        long passed = counted.stream().filter(x -> !x.run()).count();
        long total = passed == 0 ? counted.size() : passed;
        String summary = total + " passed" + (requiredKnown ? " (required ones)" : "");
        return new Outcome(Result.PASSED, List.of(), optional, summary);
    }

    private static List<String> names(List<Check> checks, CheckState state, java.util.function.Predicate<Check> and) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Check x : checks) {
            if (x.state() == state && and.test(x)) out.add(x.event().isBlank() ? x.name() : x.name() + " (" + x.event() + ")");
        }
        return new ArrayList<>(out);
    }
}
