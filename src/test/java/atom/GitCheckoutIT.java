package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The git-checkout atom, started the way the runner starts it, against a real local repository.
 *
 * <p>The checks the old {@code tools/smoke.sh} made: clone, update an existing clone, switch branch, a target
 * subdirectory, the two input refusals, and both tag messages (annotated and lightweight).
 */
class GitCheckoutIT {

    private static Path origin;
    private Atom atom;

    @BeforeAll
    static void makeOrigin() throws Exception {
        assumeTrue(available("git"), "git is required: this atom is a git client and the fixture is a real repository");
        Path root = Files.createTempDirectory(Path.of(System.getProperty("atom.scratch")), "origin-");
        origin = root.resolve("repo");
        Files.createDirectories(origin);
        git(origin, "init", "-q", "-b", "main", ".");
        git(origin, "config", "user.email", "t@t");
        git(origin, "config", "user.name", "t");
        Files.writeString(origin.resolve("app.txt"), "one\n");
        git(origin, "add", ".");
        git(origin, "commit", "-qm", "one");
        git(origin, "checkout", "-q", "-b", "feature");
        Files.writeString(origin.resolve("feature.txt"), "feature\n");
        git(origin, "add", ".");
        git(origin, "commit", "-qm", "feature");
        git(origin, "checkout", "-q", "main");
        git(origin, "tag", "-a", "v1.0.0", "-m", "first release notes");
        Files.writeString(origin.resolve("app.txt"), "one\ntwo\n");
        git(origin, "add", ".");
        git(origin, "commit", "-qm", "two");
        git(origin, "tag", "v1.1.0");
    }

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    private Path freshWorkspace(String name) throws IOException {
        return Files.createDirectories(Path.of(System.getProperty("atom.scratch"))
                .resolve("workspace-" + name + "-" + System.nanoTime()));
    }

    @Test
    @DisplayName("the clone lands in the workspace and reports the branch and commit")
    void clones() throws Exception {
        AtomResult run = atom.input("{\"url\": \"" + origin + "\", \"branch\": \"main\"}").run();

        run.assertOk();
        assertTrue(run.exists("app.txt"), "no working tree");
        assertEquals("main", run.field("GIT_BRANCH"));
        assertFalse(run.field("GIT_COMMIT").isBlank(), "no GIT_COMMIT");
    }

    @Test
    @DisplayName("a second run updates the existing clone instead of failing, and HEAD moves")
    void updatesAnExistingClone() throws Exception {
        Path shared = freshWorkspace("update");
        String url = "\"url\": \"" + origin + "\", \"branch\": \"main\", \"atomWorkspace\": \"" + shared + "\"";

        AtomResult first = atom.input("{" + url + "}").run();
        first.assertOk();
        String before = first.field("GIT_COMMIT");
        assertFalse(before.isBlank(), "no GIT_COMMIT");

        Files.writeString(origin.resolve("app.txt"), "one\ntwo\nthree\n");
        git(origin, "commit", "-qam", "three");

        AtomResult second = atom.input("{" + url + "}").run();
        second.assertOk();
        assertFalse(second.field("GIT_COMMIT").equals(before), "the update did not move HEAD");
    }

    @Test
    @DisplayName("a different branch is checked out in the same workspace")
    void switchesBranch() throws Exception {
        Path shared = freshWorkspace("branch");
        String url = "\"url\": \"" + origin + "\", \"atomWorkspace\": \"" + shared + "\"";

        atom.input("{" + url + ", \"branch\": \"main\"}").run().assertOk();
        AtomResult run = atom.input("{" + url + ", \"branch\": \"feature\"}").run();

        run.assertOk();
        // the workspace is the shared directory, not the data directory: check where the checkout went
        assertTrue(Files.isRegularFile(shared.resolve("feature.txt")), "feature branch content missing");
        assertEquals("feature", run.field("GIT_BRANCH"));
    }

    @Test
    @DisplayName("targetDir puts the checkout in a subdirectory")
    void honoursTargetDir() throws Exception {
        AtomResult run = atom.input("{\"url\": \"" + origin + "\", \"branch\": \"main\","
                + " \"targetDir\": \"src\"}").run();

        run.assertOk();
        assertTrue(run.exists("src/app.txt"), "checkout did not land in src/");
    }

    @Test
    @DisplayName("a missing url is refused")
    void refusesWithoutAUrl() throws Exception {
        AtomResult run = atom.input("{}").run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("url is required"), run.file("output.json"));
    }

    @Test
    @DisplayName("a targetDir that leaves the workspace is refused")
    void refusesToEscapeTheWorkspace() throws Exception {
        AtomResult run = atom.input("{\"url\": \"" + origin + "\", \"targetDir\": \"../escape\"}").run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("inside the workspace"), run.file("output.json"));
    }

    @Test
    @DisplayName("the most recent lightweight tag falls back to the commit subject")
    void reportsALightweightTag() throws Exception {
        AtomResult run = atom.input("{\"url\": \"" + origin + "\", \"branch\": \"main\","
                + " \"useMostRecentTag\": \"true\"}").run();

        run.assertOk();
        assertEquals("v1.1.0", run.field("GIT_TAG_NAME"));
        assertEquals("two", run.field("GIT_TAG_MESSAGE"), "a lightweight tag falls back to the commit subject");
    }

    @Test
    @DisplayName("an annotated tag reports the tag body, not the commit subject")
    void reportsAnAnnotatedTag() throws Exception {
        AtomResult run = atom.input("{\"url\": \"" + origin + "\", \"commit\": \"v1.0.0\","
                + " \"useMostRecentTag\": \"true\"}").run();

        run.assertOk();
        assertEquals("v1.0.0", run.field("GIT_TAG_NAME"));
        assertEquals("first release notes", run.field("GIT_TAG_MESSAGE"));
    }

    @Test
    @DisplayName("without useMostRecentTag no tag output appears at all")
    void leavesTagOutputsOutWhenNotAsked() throws Exception {
        AtomResult run = atom.input("{\"url\": \"" + origin + "\", \"branch\": \"main\"}").run();

        run.assertOk();
        assertFalse(run.file("output.json").contains("GIT_TAG_NAME"),
                "tag outputs appear without useMostRecentTag");
    }

    @Test
    @DisplayName("a token is used through a credential file that is gone afterwards")
    void clonesWithAToken() throws Exception {
        Path shared = freshWorkspace("token");
        AtomResult run = atom.input("{\"url\": \"" + origin + "\", \"branch\": \"main\", \"username\": \"bot\","
                        + " \"token\": \"s3cr3t\", \"atomWorkspace\": \"" + shared + "\"}")
                .run();

        run.assertOk();
        assertTrue(Files.isRegularFile(shared.resolve("app.txt")), "the clone did not land");
        Path atoms = shared.resolve(".atoms");
        assertTrue(Files.isDirectory(atoms), "no .atoms directory for the credential file");
        try (var entries = Files.list(atoms)) {
            assertEquals(0, entries.count(), "the credential file outlived the run");
        }
    }

    @Test
    @DisplayName("username and password go through the same credential file, not the command line")
    void clonesWithUsernameAndPassword() throws Exception {
        Path shared = freshWorkspace("userpass");
        AtomResult run = atom.input("{\"url\": \"" + origin + "\", \"branch\": \"main\", \"username\": \"bot\","
                        + " \"password\": \"hunter2\", \"atomWorkspace\": \"" + shared + "\"}")
                .run();

        run.assertOk();
        assertEquals("main", run.field("GIT_BRANCH"));
        try (var entries = Files.list(shared.resolve(".atoms"))) {
            assertEquals(0, entries.count(), "the credential file outlived the run");
        }
    }

    @Test
    @DisplayName("an update checks out the commit that was named")
    void updatesToANamedCommit() throws Exception {
        Path shared = freshWorkspace("commit");
        String url = "\"url\": \"" + origin + "\", \"branch\": \"main\", \"atomWorkspace\": \"" + shared + "\"";
        AtomResult first = atom.input("{" + url + "}").run();
        first.assertOk();
        String wanted = git(origin, "rev-parse", "HEAD~1").trim();

        AtomResult run = atom.input("{" + url + ", \"commit\": \"" + wanted + "\"}").run();

        run.assertOk();
        assertEquals(wanted, run.field("GIT_COMMIT"), "HEAD did not land on the named commit");
    }

    @Test
    @DisplayName("an update with neither branch nor commit follows the fetched head")
    void updatesToTheFetchedHead() throws Exception {
        Path shared = freshWorkspace("head");
        String url = "\"url\": \"" + origin + "\", \"atomWorkspace\": \"" + shared + "\"";
        atom.input("{" + url + ", \"branch\": \"main\"}").run().assertOk();

        AtomResult run = atom.input("{" + url + "}").run();

        run.assertOk();
        String head = run.field("GIT_COMMIT");
        assertFalse(head.isBlank(), "no GIT_COMMIT");
        assertEquals("commit", git(origin, "cat-file", "-t", head).trim(),
                "FETCH_HEAD did not land on a commit of the origin");
    }

    @Test
    @DisplayName("clean=true removes what the previous build left untracked")
    void cleansBeforeUpdating() throws Exception {
        Path shared = freshWorkspace("clean");
        String url = "\"url\": \"" + origin + "\", \"branch\": \"main\", \"atomWorkspace\": \"" + shared + "\"";
        atom.input("{" + url + "}").run().assertOk();
        Files.writeString(shared.resolve("leftover.bin"), "junk");

        AtomResult run = atom.input("{" + url + ", \"clean\": \"true\"}").run();

        run.assertOk();
        assertFalse(Files.exists(shared.resolve("leftover.bin")), "untracked file survived clean");
        assertTrue(Files.isRegularFile(shared.resolve("app.txt")), "clean removed the checked out files too");
    }

    @Test
    @DisplayName("depth limits the fetch to a shallow clone")
    void fetchesWithADepth() throws Exception {
        Path shared = freshWorkspace("depth");
        AtomResult run = atom.input("{\"url\": \"" + origin + "\", \"branch\": \"main\", \"depth\": \"1\","
                + " \"atomWorkspace\": \"" + shared + "\"}").run();

        run.assertOk();
        assertTrue(Files.isRegularFile(shared.resolve(".git/shallow")), "the fetch was not shallow");
        assertEquals("1", git(shared, "rev-list", "--count", "HEAD").trim());
    }

    @Test
    @DisplayName("tags=false leaves the tags out of the fetch")
    void canSkipTags() throws Exception {
        Path shared = freshWorkspace("notags");
        AtomResult run = atom.input("{\"url\": \"" + origin + "\", \"branch\": \"main\", \"tags\": \"false\","
                + " \"atomWorkspace\": \"" + shared + "\"}").run();

        run.assertOk();
        assertEquals("", git(shared, "tag").trim(), "tags were fetched although tags=false");
    }

    @Test
    @DisplayName("submodules=true updates them and the checkout still reports its files")
    void updatesSubmodules() throws Exception {
        AtomResult run = atom.input("{\"url\": \"" + origin + "\", \"branch\": \"main\","
                + " \"submodules\": \"true\"}").run();

        run.assertOk();
        assertEquals("main", run.field("GIT_BRANCH"));
        assertTrue(Integer.parseInt(run.field("fileCount")) >= 2, "fileCount: " + run.field("fileCount"));
    }

    @Test
    @DisplayName("a PATH without git is an environment error, not a failed fetch")
    void refusesWithoutGitOnThePath() throws Exception {
        AtomResult run = atom.input("{\"url\": \"" + origin + "\"}")
                .env("PATH", ":/nonexistent")
                .run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("git is not on the PATH"),
                "message: " + run.file("output.json"));
    }

    @Test
    @DisplayName("an unreachable repository is a third-party failure with the git tail in it")
    void refusesAnUnreachableRepository() throws Exception {
        Path missing = Files.createTempDirectory(Path.of(System.getProperty("atom.scratch")), "missing-")
                .resolve("nope");
        AtomResult run = atom.input("{\"url\": \"" + missing + "\", \"branch\": \"main\"}").run();

        run.assertRefused();
        String output = run.file("output.json");
        assertTrue(output.contains("git fetch failed"), "message: " + output);
    }

    @Test
    @DisplayName("an authentication failure is reported as a user error")
    void reportsAnAuthenticationFailure() throws Exception {
        AtomResult run = atom.input("{\"url\": \"https://example.com/repo.git\"}")
                .bin("git", "#!/bin/sh\ncase \"$1\" in\n"
                        + "  init) exit 0 ;;\n"
                        + "  remote) exit 0 ;;\n"
                        + "  fetch) echo \"fatal: could not read Username for 'https://example.com': "
                        + "terminal prompts disabled\"; exit 128 ;;\n"
                        + "  *) exit 0 ;;\nesac\n")
                .run();

        run.assertRefused();
        String output = run.file("output.json");
        assertTrue(output.contains("could not authenticate"), "message: " + output);
        assertTrue(output.contains("could not read Username"),
                "the git tail was dropped from the message: " + output);
    }

    @Test
    @DisplayName("git init failing is reported before anything else is tried")
    void refusesWhenInitFails() throws Exception {
        AtomResult run = atom.input("{\"url\": \"https://example.com/repo.git\"}")
                .bin("git", "#!/bin/sh\ncase \"$1\" in\n  init) echo 'fatal: not permitted'; exit 1 ;;\n"
                        + "  *) exit 0 ;;\nesac\n")
                .run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("git init failed"), "message: " + run.file("output.json"));
    }

    @Test
    @DisplayName("a repository whose origin cannot be added is a third-party failure")
    void refusesWhenTheRemoteCannotBeSet() throws Exception {
        AtomResult run = atom.input("{\"url\": \"https://example.com/repo.git\"}")
                .bin("git", "#!/bin/sh\ncase \"$1\" in\n"
                        + "  init) exit 0 ;;\n  remote) echo 'fatal: no such remote'; exit 1 ;;\n"
                        + "  *) exit 0 ;;\nesac\n")
                .run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("git remote failed"),
                "message: " + run.file("output.json"));
    }

    @Test
    @DisplayName("a fetch that fails on an existing clone is reported, not swallowed")
    void refusesWhenTheUpdateCannotFetch() throws Exception {
        Path repo = Files.createTempDirectory(Path.of(System.getProperty("atom.scratch")), "vanishing-")
                .resolve("repo");
        git(Files.createDirectories(Path.of(System.getProperty("atom.scratch"))), "clone", "-q", origin.toString(),
                repo.toString());
        Path shared = freshWorkspace("fetchfail");
        String url = "\"url\": \"" + repo + "\", \"branch\": \"main\", \"atomWorkspace\": \"" + shared + "\"";
        atom.input("{" + url + "}").run().assertOk();

        deleteTree(repo);
        AtomResult run = atom.input("{" + url + "}").run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("git fetch failed"), "message: " + run.file("output.json"));
    }

    @Test
    @DisplayName("an unknown commit on an existing clone fails at the checkout")
    void refusesAnUnknownCommitOnAnUpdate() throws Exception {
        Path shared = freshWorkspace("badcommit");
        String url = "\"url\": \"" + origin + "\", \"branch\": \"main\", \"atomWorkspace\": \"" + shared + "\"";
        atom.input("{" + url + "}").run().assertOk();

        AtomResult run = atom.input("{" + url + ", \"commit\": \"0000000000000000000000000000000000000000\"}").run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("git checkout 0000000000000000000000000000000000000000 failed"),
                "message: " + run.file("output.json"));
    }

    @Test
    @DisplayName("an unknown commit on a fresh clone fails with the checkout message")
    void refusesAnUnknownCommitOnAClone() throws Exception {
        AtomResult run = atom.input("{\"url\": \"" + origin + "\","
                + " \"commit\": \"0000000000000000000000000000000000000000\"}").run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("git checkout 0000000000000000000000000000000000000000 failed"),
                "message: " + run.file("output.json"));
    }

    @Test
    @DisplayName("a repository without tags says so instead of inventing a tag name")
    void reportsNoTagWhenThereIsNone() throws Exception {
        Path plain = Files.createTempDirectory(Path.of(System.getProperty("atom.scratch")), "plain-").resolve("repo");
        git(Files.createDirectories(Path.of(System.getProperty("atom.scratch"))), "clone", "-q", origin.toString(),
                plain.toString());
        git(plain, "tag", "-d", "v1.0.0");
        git(plain, "tag", "-d", "v1.1.0");

        AtomResult run = atom.input("{\"url\": \"" + plain + "\", \"branch\": \"main\","
                + " \"useMostRecentTag\": \"true\"}").run();

        run.assertOk();
        assertFalse(run.file("output.json").contains("GIT_TAG_NAME"), "a tag name appeared out of nowhere");
        assertTrue(run.console().contains("no tag reaches this commit"), "console:\\n" + run.console());
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static boolean available(String command) {
        try {
            return new ProcessBuilder(command, "--version").redirectErrorStream(true).start().waitFor() == 0;
        } catch (Exception notAvailable) {
            return false;
        }
    }

    private static String git(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(dir.toFile())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + output);
        }
        return output;
    }
}
