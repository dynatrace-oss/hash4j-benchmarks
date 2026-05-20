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

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.gradle.api.GradleException;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class MetadataLocal {

    private MetadataLocal() {}

    static void write(
            Path outFile,
            String id,
            String benchmarkArgs,
            String gitRevision,
            String commitTimestamp,
            String commitSubject,
            String releaseTag,
            Path jarPath) {

        long jarSize;
        String jarSha;
        try {
            jarSize = Files.size(jarPath);
            jarSha = sha256(jarPath);
        } catch (IOException e) {
            throw new GradleException("Could not stat / hash jar " + jarPath, e);
        }

        JsonObject submit = new JsonObject();
        submit.addProperty("id", id);
        submit.addProperty("benchmark_args", benchmarkArgs == null ? "" : benchmarkArgs);

        JsonObject hash4j = new JsonObject();
        hash4j.addProperty("git_revision", gitRevision);
        hash4j.addProperty("commit_timestamp", commitTimestamp);
        hash4j.addProperty("commit_subject", commitSubject);
        hash4j.addProperty("release_tag", releaseTag);
        hash4j.addProperty("jar_sha256", jarSha);
        hash4j.addProperty("jar_size_bytes", jarSize);

        JsonObject root = new JsonObject();
        root.addProperty("schema_version", 1);
        root.add("submit", submit);
        root.add("hash4j", hash4j);

        try (Writer w = Files.newBufferedWriter(outFile)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(root, w);
        } catch (IOException e) {
            throw new GradleException("Could not write " + outFile, e);
        }
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
    }
}
