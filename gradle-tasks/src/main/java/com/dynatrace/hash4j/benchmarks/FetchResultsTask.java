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
import org.gradle.api.GradleException;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;

import java.nio.file.Path;

public abstract class FetchResultsTask extends DefaultTask {

    @Input
    @Optional
    public abstract Property<String> getS3Key();

    @TaskAction
    public void run() {
        String key = getS3Key().getOrNull();
        if (key == null || key.isBlank()) {
            throw new GradleException(
                    "Missing required property 's3Key'. Invoke with -Ps3Key=<key from the SNS email>");
        }
        Path projectDir = getProject().getProjectDir().toPath();
        BenchmarkConfig cfg = BenchmarkConfig.loadOrBootstrap(BenchmarkConfig.configPath(projectDir));
        Path resultsRoot = projectDir.resolve("results");
        ResultImporter importer = new ResultImporter(cfg, getLogger());
        Path placed = importer.importByS3Key(key, resultsRoot);
        getLogger().lifecycle("Results imported into: {}", placed);
    }
}
