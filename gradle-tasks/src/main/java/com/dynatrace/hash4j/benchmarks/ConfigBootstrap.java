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

import java.nio.file.Path;

final class ConfigBootstrap {

    private ConfigBootstrap() {}

    /**
     * Bootstraps {@code config.yaml} (unconditionally — even when {@code -Prun=} is missing, so a
     * first-time user gets the template on their very first invocation), validates the run-file
     * argument, then bootstraps the run YAML if missing. Throws a combined message describing
     * everything that needs the user's attention. Returns the resolved run YAML path on success.
     */
    static Path prepare(Path configPath, String runArg, Path projectDir) {
        boolean configCreated = BenchmarkConfig.bootstrapIfMissing(configPath);

        if (runArg == null || runArg.isBlank()) {
            StringBuilder msg = new StringBuilder();
            if (configCreated) {
                msg.append("Global config did not exist. A template has been written to:\n  ")
                        .append(configPath).append("\nEdit it with your AWS settings.\n\n");
            }
            msg.append("Missing required property 'run'. Invoke with -Prun=<path to run YAML>");
            throw new GradleException(msg.toString());
        }

        Path runPath = Path.of(runArg);
        if (!runPath.isAbsolute()) {
            runPath = projectDir.resolve(runPath).normalize();
        }
        boolean runCreated = RunConfig.bootstrapIfMissing(runPath);

        if (configCreated || runCreated) {
            StringBuilder msg = new StringBuilder();
            if (configCreated) {
                msg.append("Global config did not exist. A template has been written to:\n  ")
                        .append(configPath).append('\n');
            }
            if (runCreated) {
                msg.append("Run YAML did not exist. A template has been written to:\n  ")
                        .append(runPath).append('\n');
            }
            msg.append("Edit the file(s) above and re-run.");
            throw new GradleException(msg.toString());
        }

        return runPath;
    }
}
