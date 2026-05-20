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

import org.gradle.api.Plugin;
import org.gradle.api.Project;

public class BenchmarksPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        String run = stringProp(project, "run");

        var listBenchmarks = project.getTasks().register("listBenchmarks", ListBenchmarksTask.class, t -> {
            t.setGroup("hash4j benchmarks");
            t.setDescription(
                    "Build the JMH jar for the hash4j revision configured in the supplied run YAML "
                            + "and print every @Benchmark × @Param combination it would run. Reads "
                            + "global settings from config.yaml at the project root. "
                            + "Args: -Prun=<path to run YAML>");
            if (run != null) t.getRunFile().set(run);
        });

        project.getTasks().register("submitBenchmarks", SubmitBenchmarksTask.class, t -> {
            t.setGroup("hash4j benchmarks");
            t.setDescription(
                    "Build the JMH jar for the hash4j revision configured in the supplied run YAML "
                            + "and launch an EC2 instance to run it. Reads global settings from "
                            + "config.yaml at the project root. Depends on listBenchmarks, which prints "
                            + "the selected benchmarks and aborts on a zero-match filter. "
                            + "Args: -Prun=<path to run YAML>");
            t.dependsOn(listBenchmarks);
            if (run != null) t.getRunFile().set(run);
        });

        project.getTasks().register("fetchResults", FetchResultsTask.class, t -> {
            t.setGroup("hash4j benchmarks");
            t.setDescription(
                    "Download benchmark results from S3 by key and place them under results/<instance>/<sha>/<timestamp>/. "
                            + "Args: -Ps3Key=<key from the SNS email>");
            String s3Key = stringProp(project, "s3Key");
            if (s3Key != null) t.getS3Key().set(s3Key);
        });
    }

    private static String stringProp(Project project, String key) {
        if (!project.hasProperty(key)) return null;
        Object v = project.findProperty(key);
        return v == null ? null : v.toString();
    }
}
