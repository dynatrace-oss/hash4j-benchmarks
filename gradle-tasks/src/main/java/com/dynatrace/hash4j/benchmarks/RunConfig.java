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
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Run-specific parameters: the hash4j revision, the EC2 target, an optional label, and optional JMH args. */
final class RunConfig {

    static final String DEFAULT_INSTANCE_TYPE = "c8i.xlarge";
    static final String DEFAULT_AMI_ID = "ami-091138d0f0d41ff90";

    private static final String TEMPLATE = """
            # hash4j git ref (sha, tag, or branch) to benchmark. Required.
            ref: main
            # EC2 instance type to launch.
            instance_type: %s
            # Ubuntu 26.04 (resolute) AMI for your region.
            ami_id: %s
            # Optional human-readable label for this run. When set, it is included in the
            # generated run id (hash4j-benchmark[-<label>]-<random>) used as the EC2 Name
            # tag, the staging directory, the SNS subject, and submit.id in metadata.json.
            label:
            # Optional extra JMH args passed to the benchmark jar. The first positional
            # argument is a regex over fully qualified @Benchmark method names; matching
            # benchmarks are run, the rest are skipped. Examples:
            #   benchmark_args: .*Murmur.*           # all benchmarks containing "Murmur"
            #   benchmark_args: ".*HashStream64.*"   # quote when the value contains shell metachars
            #   benchmark_args: HyperLogLog.*add    # methods under a HyperLogLog* class ending in "add"
            # Leave blank to run every @Benchmark in the jar.
            benchmark_args:
            """.formatted(DEFAULT_INSTANCE_TYPE, DEFAULT_AMI_ID);

    final String ref;
    final String instanceType;
    final String amiId;
    final String label;
    final String benchmarkArgs;

    private RunConfig(Map<String, Object> c, Path source) {
        Object refRaw = c.get("ref");
        if (refRaw == null) {
            throw new GradleException("Missing required field 'ref' in " + source);
        }
        String refStr = String.valueOf(refRaw).trim();
        if (refStr.isEmpty()) {
            throw new GradleException("Field 'ref' is empty in " + source);
        }
        this.ref = refStr;
        this.instanceType = nonBlankOr(c, "instance_type", DEFAULT_INSTANCE_TYPE);
        this.amiId = nonBlankOr(c, "ami_id", DEFAULT_AMI_ID);
        this.label = c.containsKey("label") && c.get("label") != null
                ? String.valueOf(c.get("label")).trim()
                : "";
        this.benchmarkArgs = c.containsKey("benchmark_args") && c.get("benchmark_args") != null
                ? String.valueOf(c.get("benchmark_args"))
                : "";
    }

    static RunConfig loadFrom(Path path) {
        if (!Files.exists(path)) {
            throw new GradleException("Run config file not found: " + path);
        }
        Map<String, Object> c;
        try (Reader r = Files.newBufferedReader(path)) {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Object root = yaml.load(r);
            if (root == null) {
                throw new GradleException("Run config " + path + " is empty");
            }
            if (!(root instanceof Map<?, ?> rawMap)) {
                throw new GradleException("Run config root must be a YAML mapping in " + path);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) rawMap;
            c = typed;
        } catch (IOException | YAMLException e) {
            throw new GradleException("Could not parse run config at " + path + ": " + e.getMessage(), e);
        }
        return new RunConfig(c, path);
    }

    /** Writes the run config template to {@code path} if it does not exist. Returns true if it was created. */
    static boolean bootstrapIfMissing(Path path) {
        if (Files.exists(path)) return false;
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(path, TEMPLATE);
        } catch (IOException e) {
            throw new GradleException("Could not write run config template at " + path, e);
        }
        return true;
    }

    private static String nonBlankOr(Map<String, Object> c, String key, String defaultValue) {
        Object v = c.get(key);
        if (v == null) return defaultValue;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? defaultValue : s;
    }
}
