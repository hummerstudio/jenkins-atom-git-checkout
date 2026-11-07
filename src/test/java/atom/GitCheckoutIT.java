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
