/*
 * Copyright 2026 Dynatrace LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dynatrace.hash4j.benchmarks;

import org.gradle.api.GradleException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

final class Hash4jCheckout {

    private final Path repoPath;

    Hash4jCheckout(Path repoPath) {
        this.repoPath = repoPath;
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String slug(String ref) {
        return ref.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    Materialised materialise(String ref, Path worktreeBaseDir) {
        if (!Files.isDirectory(repoPath)) {
            throw new GradleException(
                    "hash4j_repo_path does not exist or is not a directory: " + repoPath);
        }
        ProcessResult isRepo = run(repoPath, "git", "rev-parse", "--git-dir");
        if (isRepo.exitCode != 0) {
            throw new GradleException(
                    "hash4j_repo_path is not a git repository: " + repoPath
                            + "\n" + isRepo.output);
        }

        ProcessResult resolved = run(repoPath, "git", "rev-parse", "--verify", ref + "^{commit}");
        if (resolved.exitCode != 0) {
            throw new GradleException(
                    "Revision '" + ref + "' not found in local hash4j repo at " + repoPath
                            + ".\nRun `git -C " + repoPath + " fetch --all --tags` and retry.");
        }
        String sha = resolved.output.trim();

        Path worktree = worktreeBaseDir.resolve(slug(ref));
        ensureWorktree(worktree, sha);

        ProcessResult diff = run(worktree, "git", "--no-pager", "diff", "--exit-code", "HEAD");
        if (diff.exitCode != 0) {
            throw new GradleException(
                    "Worktree at " + worktree + " has uncommitted changes vs " + sha + "."
                            + " Refusing to run a benchmark whose source state can't be reproduced from the SHA alone."
                            + "\nTo recover, either delete the worktree directory (it will be re-created from a clean HEAD)"
                            + " or run: git -C \"" + worktree + "\" reset --hard " + sha
                            + "\nDiff (first lines):\n" + tail(diff.output, 2000));
        }

        // git show -s --format=%cI%n%s prints committer-date (strict ISO 8601) then subject.
        // %s is single-line; %cI never contains a newline. Splitting once is sufficient.
        String commitTimestamp = null;
        String commitSubject = null;
        ProcessResult show = run(repoPath, "git", "show", "-s", "--format=%cI%n%s", sha);
        if (show.exitCode == 0) {
            String[] lines = show.output.split("\\R", 2);
            if (lines.length >= 1) commitTimestamp = lines[0].trim();
            if (lines.length >= 2) commitSubject = lines[1].trim();
        }

        // `git tag --points-at` returns every tag at this commit (newline-separated, possibly
        // empty). hash4j releases tag a single commit, so the first line is the release tag.
        String releaseTag = null;
        ProcessResult tags = run(repoPath, "git", "tag", "--points-at", sha);
        if (tags.exitCode == 0) {
            String t = tags.output.trim();
            if (!t.isEmpty()) {
                int nl = t.indexOf('\n');
                releaseTag = (nl >= 0 ? t.substring(0, nl) : t).trim();
            }
        }

        return new Materialised(worktree, sha, commitTimestamp, commitSubject, releaseTag);
    }

    private void ensureWorktree(Path worktree, String sha) {
        if (Files.exists(worktree)) {
            ProcessResult head = run(worktree, "git", "rev-parse", "HEAD");
            if (head.exitCode == 0 && head.output.trim().equals(sha)) {
                return;
            }
            ProcessResult checkout = run(worktree, "git", "checkout", sha);
            if (checkout.exitCode != 0) {
                throw new GradleException(
                        "Could not switch existing worktree at " + worktree + " to " + sha
                                + ".\n" + checkout.output);
            }
            return;
        }
        try {
            Files.createDirectories(worktree.getParent());
        } catch (IOException e) {
            throw new GradleException("Could not create worktree parent dir: " + worktree.getParent(), e);
        }
        ProcessResult add = run(repoPath, "git", "worktree", "add", worktree.toString(), sha);
        if (add.exitCode == 0) {
            return;
        }
        ProcessResult prune = run(repoPath, "git", "worktree", "prune");
        if (prune.exitCode != 0) {
            throw new GradleException(
                    "git worktree add failed and prune also failed.\nadd: " + add.output
                            + "\nprune: " + prune.output);
        }
        ProcessResult retry = run(repoPath, "git", "worktree", "add", worktree.toString(), sha);
        if (retry.exitCode != 0) {
            throw new GradleException(
                    "git worktree add failed (after prune) for " + worktree + " at " + sha
                            + ".\n" + retry.output);
        }
    }

    Path buildJmhJar(Path worktree) {
        String wrapper = isWindows() ? "gradlew.bat" : "gradlew";
        Path wrapperPath = worktree.resolve(wrapper);
        if (!Files.exists(wrapperPath)) {
            throw new GradleException(
                    "Expected Gradle wrapper at " + wrapperPath + " (hash4j checkout does not look right)");
        }
        if (!isWindows()) {
            try {
                wrapperPath.toFile().setExecutable(true);
            } catch (SecurityException ignored) {
            }
        }

        ProcessResult build = run(worktree, wrapperPath.toString(), "jmhJar");
        if (build.exitCode != 0) {
            throw new GradleException(
                    "hash4j jmhJar build failed (exit " + build.exitCode + ").\n"
                            + "Tail of output:\n" + tail(build.output, 8000));
        }

        Path jar = findNewestJmhJar(worktree);
        if (jar == null) {
            throw new GradleException("No *-jmh.jar found under " + worktree + " after build");
        }
        return jar;
    }

    private static Path findNewestJmhJar(Path root) {
        List<Path> candidates = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    if (name.endsWith("-jmh.jar")) {
                        candidates.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new GradleException("Could not scan for jmh jar under " + root, e);
        }
        candidates.sort(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed());
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private static ProcessResult run(Path cwd, String... cmd) {
        // redirectErrorStream(true) merges stderr into stdout, so a single readAllBytes()
        // on the calling thread can drain everything safely. Without the merge we'd have
        // to drain stdout and stderr concurrently to avoid the OS pipe-buffer deadlock
        // (a cold `gradlew jmhJar` easily writes > 64 KB of warnings to stderr).
        try {
            Process p = new ProcessBuilder(cmd)
                    .directory(cwd.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(30, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new GradleException("Command timed out after 30 min: " + String.join(" ", cmd));
            }
            return new ProcessResult(p.exitValue(), output);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new GradleException("Failed to run: " + String.join(" ", cmd), e);
        }
    }

    private static String tail(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(s.length() - max);
    }

    static final class Materialised {
        final Path worktree;
        final String sha;
        final String commitTimestamp;
        final String commitSubject;
        final String releaseTag;

        Materialised(Path worktree, String sha,
                            String commitTimestamp, String commitSubject, String releaseTag) {
            this.worktree = worktree;
            this.sha = sha;
            this.commitTimestamp = commitTimestamp;
            this.commitSubject = commitSubject;
            this.releaseTag = releaseTag;
        }
    }

    private static final class ProcessResult {
        final int exitCode;
        final String output;

        ProcessResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
