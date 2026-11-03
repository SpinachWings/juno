package dev.juno.runtime;

import dev.juno.model.Agent;
import dev.juno.model.Project;
import dev.juno.model.Profile;
import dev.juno.model.Settings;
import dev.juno.provider.AgentEvent;
import dev.juno.provider.claude.ClaudeCodeProvider;
import dev.juno.skills.SkillLibrary;
import dev.juno.skills.SkillResolver;
import dev.juno.store.JunoHome;
import dev.juno.store.SettingsStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Manual check against the real Claude Code CLI (haiku, low effort; not a unit test) of the coder's new first steps:
 * it asks for the branch before touching anything, says so (and never offers git init) in a folder without git, and
 * works on the branch it was given. Usage: {@code java ... CoderLive <scratch dir>}.
 */
public final class CoderLive {
    public static void main(String[] args) throws Exception {
        Path scratch = Path.of(args[0]);
        Path repo = Files.createDirectories(scratch.resolve("repo"));
        Path plain = Files.createDirectories(scratch.resolve("plain"));
        git(repo, "init", "-q", "-b", "main");
        Files.writeString(repo.resolve("a.txt"), "one\n");
        git(repo, "add", "-A");
        git(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "init");
        git(repo, "branch", "develop");

        JunoHome home = new JunoHome(scratch.resolve("home"));
        SettingsStore ss = new SettingsStore(home);
        Settings s = ss.load();
        SkillLibrary lib = new SkillLibrary(home);
        lib.refreshBundled(s);
        Profile coder = s.profiles.stream().filter(p -> p.name.equals("coder")).findFirst().orElseThrow();
        coder.model = "haiku";
        coder.effort = "low";
        for (String id : new String[] {"p1", "p2"}) {
            Project p = new Project();
            p.id = id;
            p.name = id.equals("p1") ? "repo" : "plain";
            p.folder = (id.equals("p1") ? repo : plain).toString();
            s.projects.add(p);
        }
        ss.save(s);
        Settings[] holder = {s};
        AgentRuntime rt = new AgentRuntime(home, new ClaudeCodeProvider(scratch.resolve("claude"), home), () -> holder[0],
                () -> ss.save(holder[0]), lib, new SkillResolver(lib));
        rt.addListener(new AgentRuntime.Listener() {
            @Override public void event(Agent a, AgentEvent e) {
                if (e instanceof AgentEvent.Permission p && p.answer() == null) {
                    new Thread(() -> rt.answer(a, p.requestId(), AgentRuntime.Answer.ALLOW_ONCE, null)).start();
                }
            }
        });
        rt.start();

        Agent cody = rt.create("cody", "coder", "p1", null);
        Agent pia = rt.create("pia", "coder", "p2", null);
        String task = "Create a file called hello.txt containing the single word hi.";
        rt.send(cody, task, "user");
        rt.send(pia, task, "user");
        wait(cody);
        wait(pia);
        System.out.println("=== cody (git repo) first reply:\n" + reply(rt, cody));
        System.out.println("hello.txt exists before answering: " + Files.exists(repo.resolve("hello.txt")));
        System.out.println("status: " + cody.statusText());
        System.out.println("=== pia (no git) reply:\n" + reply(rt, pia));
        System.out.println("pia folder has .git: " + Files.exists(plain.resolve(".git")) + ", hello.txt: "
                + Files.exists(plain.resolve("hello.txt")));

        rt.send(cody, "New branch feature_123, starting from main.", "user");
        wait(cody);
        System.out.println("=== cody after the answer:\n" + reply(rt, cody));
        System.out.println("branch now: " + git(repo, "branch", "--show-current") + ", hello.txt: "
                + Files.exists(repo.resolve("hello.txt")) + ", branches: " + git(repo, "branch", "--list").replace("\n", " "));
        rt.shutdown();
        System.exit(0);
    }

    private static void wait(Agent a) throws InterruptedException {
        long until = System.currentTimeMillis() + 180_000;
        Thread.sleep(1500);
        while (System.currentTimeMillis() < until && (a.turnOpen || a.status == Agent.Status.WORKING)) Thread.sleep(500);
    }

    private static String reply(AgentRuntime rt, Agent a) {
        StringBuilder last = new StringBuilder();
        for (AgentEvent e : rt.live(a.id).history()) {
            if (e instanceof AgentEvent.UserText) last.setLength(0);
            if (e instanceof AgentEvent.Message m && m.parent() == null) last.append(m.text()).append('\n');
        }
        return last.toString().strip();
    }

    private static String git(Path where, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("git", "-C", where.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes()).strip();
        p.waitFor();
        return out;
    }
}
