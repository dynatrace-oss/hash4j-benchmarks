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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.gradle.api.GradleException;
import org.gradle.api.logging.Logger;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ResultImporter {

    private static final Pattern TIMESTAMP_S3 = Pattern.compile(
            "^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2})-(\\d{2})-(\\d{2})Z(-[A-Za-z0-9]+)?$");

    private final BenchmarkConfig cfg;
    private final Logger logger;

    ResultImporter(BenchmarkConfig cfg, Logger logger) {
        this.cfg = cfg;
        this.logger = logger;
    }

    Path importByS3Key(String rawKey, Path resultsRoot) {
        String key = normalizeKey(rawKey);
        Segments segs = parseKey(key);

        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("hash4j-benchmark-fetch-");
        } catch (IOException e) {
            throw new GradleException("Could not create temp dir", e);
        }

        try (S3Client s3 = S3Client.builder().region(Region.of(cfg.region)).build()) {
            String outputPrefix = key + "/output/";
            List<S3Object> objs = listAll(s3, cfg.bucket, outputPrefix);
            if (objs.isEmpty()) {
                throw new GradleException(
                        "No objects under s3://" + cfg.bucket + "/" + outputPrefix
                                + " — has the benchmark finished, or has the lifecycle rule already deleted it?");
            }
            for (S3Object obj : objs) {
                String relative = obj.key().substring(outputPrefix.length());
                if (relative.isEmpty()) continue;
                Path dest = tempDir.resolve(relative);
                try {
                    Files.createDirectories(dest.getParent() == null ? tempDir : dest.getParent());
                    s3.getObject(GetObjectRequest.builder()
                                    .bucket(cfg.bucket)
                                    .key(obj.key())
                                    .build(),
                            dest);
                    logger.lifecycle("  downloaded {} ({} bytes)", relative, obj.size());
                } catch (IOException e) {
                    throw new GradleException("Could not write " + dest, e);
                }
            }
        }

        Path localMetadata = tempDir.resolve("metadata.local.json");
        Path runtimeMetadata = tempDir.resolve("metadata.runtime.json");
        if (!Files.exists(localMetadata) || !Files.exists(runtimeMetadata)) {
            throw new GradleException(
                    "S3 output is missing metadata files (expected both metadata.local.json and metadata.runtime.json). "
                            + "Found at " + tempDir);
        }
        JsonObject merged = mergeMetadata(localMetadata, runtimeMetadata);
        validateMatch(merged, segs);

        String localTimestamp = reformatTimestamp(segs.timestamp);
        Path target = resultsRoot
                .resolve(segs.instanceType)
                .resolve(segs.sha)
                .resolve(localTimestamp);

        if (Files.exists(target)) {
            throw new GradleException(
                    "Target directory already exists, refusing to overwrite:\n  " + target);
        }

        try {
            Files.createDirectories(target);
            Path mergedFile = target.resolve("metadata.json");
            Gson pretty = new GsonBuilder().setPrettyPrinting().create();
            try (Writer w = Files.newBufferedWriter(mergedFile)) {
                pretty.toJson(merged, w);
            }
            moveIfExists(tempDir.resolve("jmh-results.json"), target.resolve("jmh-results.json"));
            moveIfExists(tempDir.resolve("jmh.log"), target.resolve("jmh.log"));
            moveIfExists(tempDir.resolve("runner.log"), target.resolve("runner.log"));
        } catch (IOException e) {
            throw new GradleException("Could not place results into " + target, e);
        }

        return target;
    }

    static String normalizeKey(String key) {
        if (key == null) {
            throw new GradleException("s3Key is required (-Ps3Key=...)");
        }
        String k = key.trim();
        if (k.startsWith("s3://")) {
            int slash = k.indexOf('/', "s3://".length());
            if (slash > 0) {
                k = k.substring(slash + 1);
            }
        }
        while (k.endsWith("/")) k = k.substring(0, k.length() - 1);
        return k;
    }

    Segments parseKey(String key) {
        String[] parts = key.split("/");
        if (parts.length != 4) {
            throw new GradleException(
                    "s3Key must have 4 segments: <results_prefix>/<instance_type>/<sha>/<timestamp>, got: " + key);
        }
        if (!parts[0].equals(cfg.resultsPrefix)) {
            throw new GradleException(
                    "s3Key prefix '" + parts[0] + "' does not match configured results_prefix '"
                            + cfg.resultsPrefix + "'");
        }
        return new Segments(parts[1], parts[2], parts[3]);
    }

    private static List<S3Object> listAll(S3Client s3, String bucket, String prefix) {
        List<S3Object> out = new ArrayList<>();
        String continuation = null;
        do {
            ListObjectsV2Request.Builder req = ListObjectsV2Request.builder()
                    .bucket(bucket).prefix(prefix);
            if (continuation != null) req.continuationToken(continuation);
            ListObjectsV2Response resp = s3.listObjectsV2(req.build());
            out.addAll(resp.contents());
            continuation = Boolean.TRUE.equals(resp.isTruncated()) ? resp.nextContinuationToken() : null;
        } while (continuation != null);
        out.sort(Comparator.comparing(S3Object::key));
        return out;
    }

    static JsonObject mergeMetadata(Path localJson, Path runtimeJson) {
        JsonObject local = parseObject(localJson);
        JsonObject runtime = parseObject(runtimeJson);
        JsonObject merged = new JsonObject();
        for (Map.Entry<String, JsonElement> e : local.entrySet()) {
            merged.add(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, JsonElement> e : runtime.entrySet()) {
            merged.add(e.getKey(), e.getValue());
        }
        return merged;
    }

    private static JsonObject parseObject(Path file) {
        try (Reader r = Files.newBufferedReader(file)) {
            JsonElement el = JsonParser.parseReader(r);
            if (!el.isJsonObject()) {
                throw new GradleException("Expected JSON object at top level of " + file);
            }
            return el.getAsJsonObject();
        } catch (IOException e) {
            throw new GradleException("Could not read " + file + ": " + e.getMessage(), e);
        }
    }

    private void validateMatch(JsonObject merged, Segments segs) {
        String mInstanceType = strOrNull(merged, "aws", "instance_type");
        String mSha = strOrNull(merged, "hash4j", "git_revision");
        if (mInstanceType != null && !mInstanceType.equals(segs.instanceType)) {
            throw new GradleException(
                    "Metadata aws.instance_type='" + mInstanceType
                            + "' does not match S3 key segment '" + segs.instanceType + "'");
        }
        if (mSha != null && !mSha.equals(segs.sha)) {
            throw new GradleException(
                    "Metadata hash4j.git_revision='" + mSha
                            + "' does not match S3 key segment '" + segs.sha + "'");
        }
    }

    private static String strOrNull(JsonObject root, String section, String field) {
        if (!root.has(section) || !root.get(section).isJsonObject()) return null;
        JsonObject obj = root.getAsJsonObject(section);
        if (!obj.has(field) || obj.get(field).isJsonNull()) return null;
        return obj.get(field).getAsString();
    }

    static String reformatTimestamp(String s3Timestamp) {
        Matcher m = TIMESTAMP_S3.matcher(s3Timestamp);
        if (!m.matches()) {
            throw new GradleException(
                    "Timestamp segment does not match YYYY-MM-DDTHH-MM-SSZ[-suffix]: " + s3Timestamp);
        }
        return m.group(1) + "-" + m.group(2) + "-" + m.group(3) + "-"
                + m.group(4) + "-" + m.group(5) + "-" + m.group(6);
    }

    private static void moveIfExists(Path from, Path to) throws IOException {
        if (Files.exists(from)) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static final class Segments {
        final String instanceType;
        final String sha;
        final String timestamp;

        Segments(String instanceType, String sha, String timestamp) {
            this.instanceType = instanceType;
            this.sha = sha;
            this.timestamp = timestamp;
        }
    }
}
