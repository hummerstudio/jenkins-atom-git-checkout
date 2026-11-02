package com.jenkinschina.atom.gitcheckout;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Checkout as an atom: clone or update a repository in the workspace, and hand the commit id to the next step.
 *
 * <p>Jenkins' {@code checkout scm} needs a job configured with SCM. An atom pipeline should be able to name the
 * repository itself, so this takes url/branch/commit as inputs and does the same job with the git CLI. Credentials
 * arrive as strings (username/password or a token) and are written into a 0600 credential file that git reads and
 * that never appears in the log.
 */
public class GitCheckoutAtom implements AtomTask {

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        Path workspace = context.getWorkspace();
        String url = context.getParam("url", "").trim();
        String branch = context.getParam("branch", "").trim();
        String commit = context.getParam("commit", "").trim();
        String targetDir = context.getParam("targetDir", ".").trim();
        String depth = context.getParam("depth", "").trim();
        String username = context.getParam("username", "");
        String password = context.getParam("password", "");
        String token = context.getParam("token", "");
        boolean submodules = context.getBooleanParam("submodules", false);
        boolean clean = context.getBooleanParam("clean", false);
        boolean tags = context.getBooleanParam("tags", true);

        if (url.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40001, "url is required");
        }
        if (gitVersion(workspace) == null) {
            return AtomResult.error(ErrorType.ENVIRONMENT, 40002, "git is not on the PATH of this agent");
        }

        Path target = workspace.resolve(targetDir).normalize();
        if (!target.startsWith(workspace)) {
            return AtomResult.error(ErrorType.USER, 40003, "targetDir must stay inside the workspace: " + targetDir);
        }
        Files.createDirectories(target);

        Path credentialsFile = null;
        try {
            List<String> before = new ArrayList<>();
            if (!token.isBlank()) {
                // https://<token>@host/... — the token only ever lives in the credential file
                credentialsFile = writeCredentialsFile(workspace, username, token);
                before.add("-c");
                before.add("credential.helper=store --file=" + credentialsFile);
            } else if (!username.isBlank() && !password.isBlank()) {
                credentialsFile = writeCredentialsFile(workspace, username, password);
                before.add("-c");
                before.add("credential.helper=store --file=" + credentialsFile);
            }

            boolean existing = Files.isDirectory(target.resolve(".git"));
            List<String> command = new ArrayList<>();
            command.add("git");
            command.addAll(before);
            if (existing) {
                context.log("[git] updating " + targetDir);
                Result fetched = exec(target, "git", concat(before, "fetch", "--prune", "--tags", "origin"));
                if (!fetched.ok()) {
                    return AtomResult.error(ErrorType.THIRD_PART, 40004, "git fetch failed: " + fetched.tail());
                }
                if (clean) {
                    Result cleaned = exec(target, "git", concat(before, "clean", "-xdf"));
                    if (!cleaned.ok()) {
                        return AtomResult.error(ErrorType.THIRD_PART, 40005, "git clean failed: " + cleaned.tail());
                    }
                }
                // the local branch does not track anything by default, so reset it onto the fetched remote ref
                String ref;
                List<String> checkoutArgs;
                if (!commit.isBlank()) {
                    ref = commit;
                    checkoutArgs = List.of("checkout", "-f", commit);
                } else if (!branch.isBlank()) {
                    ref = branch;
                    checkoutArgs = List.of("checkout", "-f", "-B", branch, "origin/" + branch);
                } else {
                    ref = "FETCH_HEAD";
                    checkoutArgs = List.of("checkout", "-f", "FETCH_HEAD");
                }
                Result checkedOut = exec(target, "git", concat(before, checkoutArgs.toArray(new String[0])));
                if (!checkedOut.ok()) {
                    return AtomResult.error(ErrorType.THIRD_PART, 40006,
                            "git checkout " + ref + " failed: " + checkedOut.tail());
                }
            } else {
                // init + fetch rather than clone: a Jenkins workspace already holds files (input.json, logs), and
                // `git clone` refuses a non-empty directory.
                context.log("[git] fetching " + url + (branch.isEmpty() ? "" : " (" + branch + ")"));
                Result init = exec(target, "git", List.of("init", "-q"));
                if (!init.ok()) {
                    return AtomResult.error(ErrorType.THIRD_PART, 40007, "git init failed: " + init.tail());
                }
                Result remoteSet = exec(target, "git", concat(before, "remote", "get-url", "origin"));
                String remoteCommand = remoteSet.ok() ? "set-url" : "add";
                Result remote = exec(target, "git", concat(before, "remote", remoteCommand, "origin", url));
                if (!remote.ok()) {
                    return AtomResult.error(ErrorType.THIRD_PART, 40008, "git remote failed: " + remote.tail());
                }
                List<String> fetch = new ArrayList<>(List.of("git"));
                fetch.addAll(before);
                fetch.add("fetch");
                if (!depth.isEmpty()) {
                    fetch.add("--depth");
                    fetch.add(depth);
                }
                // 要导出最近标签时不能把标签关掉：--no-tags 之后 git describe 只会报
                // "No names found, cannot describe anything"，而那个字符串会被当成标签名传下去。
                boolean wantTags = tags || context.getBooleanParam("useMostRecentTag", false);
                if (!wantTags) {
                    fetch.add("--no-tags");
                } else {
                    // `git fetch origin main`（分支简写）不会自动跟标签，只有显式 refspec 才跟——
                    // 实测同一个仓库，加 --tags 之后 refs/tags 才出现，否则 git describe 一直说没有名字。
                    fetch.add("--tags");
                }
                fetch.add("origin");
                if (!branch.isEmpty()) {
                    fetch.add(branch);
                }
                Result fetched = exec(target, fetch.get(0), fetch.subList(1, fetch.size()).toArray(new String[0]));
                if (!fetched.ok()) {
                    if (fetched.output().contains("Authentication failed")
                            || fetched.output().contains("could not read Username")) {
                        return AtomResult.error(ErrorType.USER, 40009,
                                "git could not authenticate: " + fetched.tail());
                    }
                    return AtomResult.error(ErrorType.THIRD_PART, 40010, "git fetch failed: " + fetched.tail());
                }
                String ref = !commit.isBlank() ? commit : (branch.isEmpty() ? "FETCH_HEAD" : branch);
                List<String> checkout = new ArrayList<>(List.of("git"));
                checkout.addAll(before);
                checkout.add("checkout");
                checkout.add("-f");
                if (!branch.isEmpty() && commit.isBlank()) {
                    checkout.add("-B");
                    checkout.add(branch);
                    checkout.add("FETCH_HEAD");
                } else {
                    checkout.add(ref);
                }
                Result checkedOut = exec(target, checkout.get(0), checkout.subList(1, checkout.size()).toArray(new String[0]));
                if (!checkedOut.ok()) {
                    return AtomResult.error(ErrorType.USER, 40011,
                            "git checkout " + ref + " failed: " + checkedOut.tail());
                }
            }

            if (submodules) {
                Result updated = exec(target, "git", concat(before, "submodule", "update", "--init", "--recursive"));
                if (!updated.ok()) {
                    return AtomResult.error(ErrorType.THIRD_PART, 40010,
                            "git submodule update failed: " + updated.tail());
                }
            }

            String head = firstLine(exec(target, "git", List.of("rev-parse", "HEAD")).output());
            String headBranch = firstLine(exec(target, "git", List.of("rev-parse", "--abbrev-ref", "HEAD")).output());
            String remotes = firstLine(exec(target, "git", List.of("config", "--get", "remote.origin.url")).output());
            context.log("[git] " + (headBranch.isBlank() ? "detached" : headBranch) + " @ " + shortSha(head));

            int fileCount = 0;
            try (var walk = Files.walk(target, 8)) {
                fileCount = (int) walk.filter(Files::isRegularFile)
                        .filter(path -> !path.toString().contains("/.git/"))
                        .limit(20000)
                        .count();
            }

            AtomResult result = AtomResult.success()
                    .data("GIT_COMMIT", head)
                    .data("GIT_BRANCH", headBranch)
                    .data("GIT_URL", remotes.isEmpty() ? url : remotes)
                    .data("GIT_CHECKOUT_DIR", targetDir)
                    .data("fileCount", String.valueOf(fileCount));

            if (context.getBooleanParam("useMostRecentTag", false)) {
                // git-tag-message 的时机在这里：版本号、通知、发布备注都要在 checkout 之后立刻拿到；
                // 放到流程末尾（push）就晚了。最近的标签按 git describe 定义，消息取附注标签正文，
                // 轻量标签没有正文，回落到它指向的提交说明。
                Result described = exec(target, "git", List.of("describe", "--tags", "--abbrev=0"));
                String tagName = described.ok() ? firstLine(described.output()) : "";
                if (tagName.isBlank()) {
                    context.log("[git] useMostRecentTag: no tag reaches this commit");
                } else {
                    Result contents = exec(target, "git",
                            List.of("for-each-ref", "--format=%(contents)", "refs/tags/" + tagName));
                    String message = contents.ok() ? firstLine(contents.output()) : "";
                    if (message.isBlank()) {
                        Result subject = exec(target, "git", List.of("log", "-1", "--format=%s", tagName));
                        message = subject.ok() ? firstLine(subject.output()) : "";
                    }
                    context.log("[git] most recent tag: " + tagName);
                    result = result
                            .data("GIT_TAG_NAME", tagName)
                            .data("GIT_TAG_MESSAGE", message);
                }
            }
            return result;
        } finally {
            if (credentialsFile != null) {
                Files.deleteIfExists(credentialsFile);
            }
        }
    }

    /** A 0600 file git can read; the password never enters the command line or the log. */
    private static Path writeCredentialsFile(Path workspace, String username, String secret) throws Exception {
        Path dir = workspace.resolve(".atoms");
        Files.createDirectories(dir);
        Path file = Files.createTempFile(dir, "git-credentials", ".txt");
        String value = "https://" + enc(username.isEmpty() ? "git" : username) + ":" + enc(secret) + "@github.com\n";
        Files.writeString(file, value);
        try {
            Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            file.toFile().setReadable(false, false);
            file.toFile().setReadable(true, true);
        }
        return file;
    }

    private static String enc(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String gitVersion(Path workspace) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String entry : path.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            Path candidate = Path.of(entry).resolve("git");
            if (Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath().toString();
            }
        }
        return null;
    }

    private static List<String> concat(List<String> prefix, String... rest) {
        List<String> all = new ArrayList<>(prefix);
        all.addAll(List.of(rest));
        return all;
    }

    private static String shortSha(String sha) {
        return sha.length() > 8 ? sha.substring(0, 8) : sha;
    }

    private static Result exec(Path directory, String program, List<String> args) {
        String[] command = new String[args.size() + 1];
        command[0] = program;
        for (int i = 0; i < args.size(); i++) {
            command[i + 1] = args.get(i);
        }
        return exec(directory, command[0], java.util.Arrays.copyOfRange(command, 1, command.length));
    }

    private static Result exec(Path directory, String program, String... args) {
        try {
            List<String> command = new ArrayList<>();
            command.add(program);
            command.addAll(List.of(args));
            ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
            // git must not stop to ask for a password: a build has nobody to type one
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            builder.environment().put("GIT_ASKPASS", "echo");
            Process process = builder.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.toLowerCase(java.util.Locale.ROOT).contains("password")
                            || line.contains("@github.com")) {
                        continue; // never echo a credential, whatever git decides to print
                    }
                    output.append(line).append('\n');
                    if (output.length() > 100_000) {
                        output.delete(0, 50_000);
                    }
                }
            }
            return new Result(process.waitFor(), output.toString());
        } catch (Exception e) {
            return new Result(-1, String.valueOf(e));
        }
    }

    private static String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.strip().lines().findFirst().orElse("").trim();
    }

    private record Result(int code, String output) {
        boolean ok() {
            return code == 0;
        }

        String tail() {
            String[] lines = output.strip().split("\n");
            StringBuilder out = new StringBuilder();
            for (int i = Math.max(0, lines.length - 6); i < lines.length; i++) {
                out.append(lines[i]).append('\n');
            }
            return out.toString().strip();
        }
    }
}
