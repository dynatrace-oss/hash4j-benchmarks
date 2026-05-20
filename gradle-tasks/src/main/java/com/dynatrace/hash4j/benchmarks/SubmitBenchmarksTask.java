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

import org.gradle.api.DefaultTask;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public abstract class SubmitBenchmarksTask extends DefaultTask {

    private static final DateTimeFormatter S3_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'").withZone(ZoneOffset.UTC);

    @Input
    @Optional
    public abstract Property<String> getRunFile();

    @TaskAction
    public void run() throws IOException {
        Path projectDir = getProject().getProjectDir().toPath();
        Path configPath = BenchmarkConfig.configPath(projectDir);
        Path runPath = ConfigBootstrap.prepare(configPath, getRunFile().getOrNull(), projectDir);
        BenchmarkConfig cfg = BenchmarkConfig.loadFrom(configPath);
        RunConfig runCfg = RunConfig.loadFrom(runPath);
        getLogger().lifecycle("Loaded global config from {}", configPath);
        getLogger().lifecycle("Loaded run config from    {}", runPath);

        String ref = runCfg.ref;
        // Run id format: hash4j-benchmark[-<sanitised-label>]-<16-hex-random>. Used as the EC2
        // Name tag, the staging dir, the S3 key suffix, the SNS subject, and submit.id in
        // metadata.json — same string everywhere so a result is traceable from any side.
        String uniqueSuffix = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String labelSegment = runCfg.label.isBlank() ? "" : "-" + sanitiseName(runCfg.label);
        String id = "hash4j-benchmark" + labelSegment + "-" + uniqueSuffix;
        String benchmarkArgs = runCfg.benchmarkArgs;

        Path buildDir = projectDir.resolve("build");
        Path worktreeBase = buildDir.resolve("hash4j-worktree");
        Path stagingBase = buildDir.resolve("staging");
        Files.createDirectories(stagingBase);

        Path repoPath = Path.of(cfg.hash4jRepoPath);
        if (!repoPath.isAbsolute()) {
            repoPath = projectDir.resolve(repoPath).normalize();
        }
        Hash4jCheckout co = new Hash4jCheckout(repoPath);
        long t0 = System.nanoTime();
        Hash4jCheckout.Materialised mat = co.materialise(ref, worktreeBase);
        getLogger().lifecycle("hash4j worktree at {} (revision {}) [{}]",
                mat.worktree, mat.sha, elapsed(t0));

        getLogger().lifecycle("Building jmhJar in {} ...", mat.worktree);
        long t1 = System.nanoTime();
        Path jar = co.buildJmhJar(mat.worktree);
        getLogger().lifecycle("Built jmh jar: {} [{}]", jar, elapsed(t1));

        try (AwsRunner aws = new AwsRunner(cfg, getLogger())) {
            long t3 = System.nanoTime();
            String arn = aws.checkAuth();
            getLogger().lifecycle("AWS identity: {} [{}]", arn, elapsed(t3));
            aws.warnIfNoLifecycleRule();

            Instant nowUtc = Instant.now();
            // The unique suffix in the run id also serves as the S3 timestamp suffix:
            // the timestamp stays human-sortable while the suffix guarantees uniqueness.
            String timestampSegment = S3_TS.format(nowUtc) + "-" + uniqueSuffix;
            String s3KeyNoTrailingSlash = cfg.resultsPrefix + "/" + runCfg.instanceType
                    + "/" + mat.sha + "/" + timestampSegment;
            String s3Key = s3KeyNoTrailingSlash + "/";
            getLogger().lifecycle("S3 key: {}", s3KeyNoTrailingSlash);

            Path stagingRun = stagingBase.resolve(id);
            Path stagingInput = stagingRun.resolve("input");
            Files.createDirectories(stagingInput);

            Files.copy(jar, stagingInput.resolve("jmh.jar"));
            Files.writeString(stagingInput.resolve("monitor.sh"), monitorScript());

            MetadataLocal.write(
                    stagingInput.resolve("metadata.local.json"),
                    id,
                    benchmarkArgs,
                    mat.sha,
                    mat.commitTimestamp,
                    mat.commitSubject,
                    mat.releaseTag,
                    stagingInput.resolve("jmh.jar"));

            String userData = UserDataScript.generate(cfg, s3Key, id, benchmarkArgs);
            Files.writeString(stagingInput.resolve("run.sh"), userData);

            getLogger().lifecycle("Uploading staging to s3://{}/{}input/", cfg.bucket, s3Key);
            long t4 = System.nanoTime();
            aws.uploadInputDir(stagingInput, s3Key);
            getLogger().lifecycle("S3 upload done [{}]", elapsed(t4));

            long t5 = System.nanoTime();
            AwsRunner.LaunchResult launched = aws.launchInstance(userData, id, runCfg.amiId, runCfg.instanceType);
            getLogger().lifecycle("EC2 launch done [{}]", elapsed(t5));
            String host = launched.host == null || launched.host.isBlank()
                    ? "<pending — query EC2 for IP>"
                    : launched.host;

            String summary = """

                    ============================================================
                    Run id:    %s
                    Ref:       %s  (%s)
                    Jar:       %s
                    Instance:  %s  (%s)
                    Host:      %s
                    S3 key:    %s

                    Once the benchmark finishes you will receive an SNS email.
                    To fetch the results:
                      Linux/macOS: ./gradlew fetchResults -Ps3Key="%s"
                      Windows:     .\\gradlew.bat fetchResults "-Ps3Key=%s"

                    If no email arrives, poll the instance:
                      aws ec2 describe-instances --region %s --instance-ids %s
                    ============================================================
                    """
                    .formatted(
                            id,
                            ref, mat.sha,
                            jar.getFileName(),
                            launched.instanceId, runCfg.instanceType,
                            host,
                            s3KeyNoTrailingSlash,
                            s3KeyNoTrailingSlash,
                            s3KeyNoTrailingSlash,
                            cfg.region, launched.instanceId);
            getLogger().lifecycle(summary);

            Files.writeString(stagingBase.resolve("benchmarks.txt"),
                    summary,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    private static String elapsed(long startNanos) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000L;
        if (ms < 1000) return ms + " ms";
        long totalSec = ms / 1000;
        if (totalSec < 60) return String.format("%.1f s", ms / 1000.0);
        return String.format("%dm %ds", totalSec / 60, totalSec % 60);
    }

    private static String sanitiseName(String s) {
        String t = s.replaceAll("[^A-Za-z0-9._-]", "-");
        if (t.isBlank()) return "benchmark";
        return t.length() > 64 ? t.substring(0, 64) : t;
    }

    private static String monitorScript() {
        return """
                #!/bin/bash
                # Print the most recent JMH trial context for status-watching.
                grep -B 3 -A 100 "$(grep "# Run progress\\|# Run complete" /opt/jmh-benchmark-run/output/jmh.log | tail -n 1)" /opt/jmh-benchmark-run/output/jmh.log
                """;
    }

}
