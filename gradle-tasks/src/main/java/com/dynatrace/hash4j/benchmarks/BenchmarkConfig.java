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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class BenchmarkConfig {

    static final String CONFIG_FILE = "config.yaml";

    private static final String TEMPLATE = """
            region: us-east-1
            bucket: hash4j-benchmarks
            # Bucket lifecycle rule should target this prefix (7-day expiration recommended).
            results_prefix: runs
            # EC2 key-pair NAME as registered in AWS (e.g. my-key-pair), NOT a path to the .pem file.
            # The private key in ~/.ssh/<key_name>.pem is only needed when you SSH into the instance.
            key_name: my-key-pair
            security_group: sg-xxxxxxxxxxxx
            instance_profile: hash4j-benchmark-profile
            sns_topic_arn: arn:aws:sns:us-east-1:123:hash4j-benchmark-complete
            # Local clone of dynatrace-oss/hash4j; relative paths are resolved against this repo's root.
            # Default ../hash4j assumes a sibling checkout. submitBenchmarks does NOT fetch from GitHub —
            # if the requested ref is missing, run `git fetch` in this checkout first.
            hash4j_repo_path: ../hash4j
            remote_java_package: openjdk-25-jdk
            max_runtime_hours: 24
            # Root EBS volume size in GiB (gp3, delete-on-termination). Minimum 8.
            ebs_volume_gb: 30
            # Optional list of extra EC2 tags applied to the launched instance. Example:
            #   - Key: owner
            #     Value: alice@example.com
            #   - Key: cost-center
            #     Value: research
            extra_tags:
            """;

    final String region;
    final String bucket;
    final String resultsPrefix;
    final String keyName;
    final String securityGroup;
    final String instanceProfile;
    final String snsTopicArn;
    final String hash4jRepoPath;
    final String remoteJavaPackage;
    final double maxRuntimeHours;
    final int ebsVolumeGb;
    final List<ExtraTag> extraTags;

    private BenchmarkConfig(Map<String, Object> c, Path source) {
        this.region = req(c, "region", source);
        this.bucket = req(c, "bucket", source);
        this.resultsPrefix = stripSlashes(req(c, "results_prefix", source));
        this.keyName = req(c, "key_name", source);
        if (this.keyName.contains("/") || this.keyName.contains("\\") || this.keyName.endsWith(".pem")) {
            throw new GradleException(
                    "'key_name' in " + source + " must be the EC2 key-pair NAME (as registered in AWS), "
                            + "not a file path. Got: '" + this.keyName + "'. "
                            + "Use e.g. \"my-ec2-keypair\" — the .pem file lives in ~/.ssh/ and is only needed for SSH access.");
        }
        this.securityGroup = req(c, "security_group", source);
        this.instanceProfile = req(c, "instance_profile", source);
        this.snsTopicArn = req(c, "sns_topic_arn", source);
        this.hash4jRepoPath = req(c, "hash4j_repo_path", source);
        this.remoteJavaPackage = c.containsKey("remote_java_package") && c.get("remote_java_package") != null
                ? String.valueOf(c.get("remote_java_package"))
                : "openjdk-25-jdk";
        this.maxRuntimeHours = c.containsKey("max_runtime_hours") && c.get("max_runtime_hours") != null
                ? asDouble(c.get("max_runtime_hours"), "max_runtime_hours", source)
                : 24.0;
        int volumeGb = c.containsKey("ebs_volume_gb") && c.get("ebs_volume_gb") != null
                ? asInt(c.get("ebs_volume_gb"), "ebs_volume_gb", source)
                : 30;
        if (volumeGb < 8) {
            throw new GradleException(
                    "'ebs_volume_gb' in " + source + " must be >= 8 (AMI minimum); got " + volumeGb);
        }
        this.ebsVolumeGb = volumeGb;
        this.extraTags = parseExtraTags(c, source);
    }

    static BenchmarkConfig loadFrom(Path path) {
        if (!Files.exists(path)) {
            throw new GradleException("Config file not found: " + path);
        }
        Map<String, Object> c;
        try (Reader r = Files.newBufferedReader(path)) {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Object root = yaml.load(r);
            if (!(root instanceof Map<?, ?> rawMap)) {
                throw new GradleException("Config root must be a YAML mapping in " + path);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) rawMap;
            c = typed;
        } catch (IOException | YAMLException e) {
            throw new GradleException("Could not parse config at " + path + ": " + e.getMessage(), e);
        }
        return new BenchmarkConfig(c, path);
    }

    static BenchmarkConfig loadOrBootstrap(Path path) {
        if (bootstrapIfMissing(path)) {
            throw new GradleException(
                    "Config file did not exist. A template has been written to:\n  "
                            + path
                            + "\nEdit it with your AWS settings and re-run.");
        }
        return loadFrom(path);
    }

    /** Writes the global config template to {@code path} if it does not exist. Returns true if it was created. */
    static boolean bootstrapIfMissing(Path path) {
        if (Files.exists(path)) return false;
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            Files.writeString(path, TEMPLATE);
        } catch (IOException e) {
            throw new GradleException("Could not write config template at " + path, e);
        }
        return true;
    }

    static Path configPath(Path projectDir) {
        return projectDir.resolve(CONFIG_FILE);
    }

    private static String req(Map<String, Object> c, String key, Path source) {
        Object v = c.get(key);
        if (v == null) {
            throw new GradleException("Missing required config field '" + key + "' in " + source);
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            throw new GradleException("Config field '" + key + "' is empty in " + source);
        }
        return s;
    }

    private static double asDouble(Object v, String key, Path source) {
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            throw new GradleException(
                    "Config field '" + key + "' must be a number in " + source + "; got: " + v);
        }
    }

    private static int asInt(Object v, String key, Path source) {
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new GradleException(
                    "Config field '" + key + "' must be an integer in " + source + "; got: " + v);
        }
    }

    private static String stripSlashes(String s) {
        String t = s;
        while (t.startsWith("/")) t = t.substring(1);
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }

    private static List<ExtraTag> parseExtraTags(Map<String, Object> c, Path source) {
        List<ExtraTag> out = new ArrayList<>();
        Object raw = c.get("extra_tags");
        if (raw == null) return out;
        if (!(raw instanceof List<?> list)) {
            throw new GradleException("'extra_tags' must be a list in " + source);
        }
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> tag)) {
                throw new GradleException(
                        "Each extra_tags entry must be a mapping with Key and Value (in " + source + ")");
            }
            Object kRaw = tag.get("Key");
            Object vRaw = tag.get("Value");
            String k = kRaw == null ? "" : String.valueOf(kRaw);
            String v = vRaw == null ? "" : String.valueOf(vRaw);
            if (k.isBlank()) {
                throw new GradleException("extra_tags entry is missing 'Key' in " + source);
            }
            out.add(new ExtraTag(k, v));
        }
        return out;
    }

    static final class ExtraTag {
        final String key;
        final String value;

        ExtraTag(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }
}
