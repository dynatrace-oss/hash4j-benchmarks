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

import java.nio.file.Path;

public abstract class ListBenchmarksTask extends DefaultTask {

    @Input
    @Optional
    public abstract Property<String> getRunFile();

    @TaskAction
    public void run() {
        Path projectDir = getProject().getProjectDir().toPath();
        Path configPath = BenchmarkConfig.configPath(projectDir);
        Path runPath = ConfigBootstrap.prepare(configPath, getRunFile().getOrNull(), projectDir);
        BenchmarkConfig cfg = BenchmarkConfig.loadFrom(configPath);
        RunConfig runCfg = RunConfig.loadFrom(runPath);
        getLogger().lifecycle("Loaded global config from {}", configPath);
        getLogger().lifecycle("Loaded run config from    {}", runPath);

        String ref = runCfg.ref;

        Path repoPath = Path.of(cfg.hash4jRepoPath);
        if (!repoPath.isAbsolute()) {
            repoPath = projectDir.resolve(repoPath).normalize();
        }
        Path worktreeBase = projectDir.resolve("build").resolve("hash4j-worktree");

        Hash4jCheckout co = new Hash4jCheckout(repoPath);
        Hash4jCheckout.Materialised mat = co.materialise(ref, worktreeBase);
        getLogger().lifecycle("hash4j worktree at {} (revision {})", mat.worktree, mat.sha);

        getLogger().lifecycle("Building jmhJar in {} ...", mat.worktree);
        Path jar = co.buildJmhJar(mat.worktree);
        getLogger().lifecycle("Built jmh jar: {}", jar);

        JmhListing.printSelectedBenchmarks(jar, runCfg.benchmarkArgs, getLogger());
    }
}
