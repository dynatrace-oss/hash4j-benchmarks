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
import org.gradle.api.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class JmhListing {

    private JmhListing() {}

    /**
     * Runs the freshly built JMH jar with the configured benchmark args plus {@code -lp},
     * prints the resulting benchmark × parameter list, and returns the number of selected
     * benchmarks. Throws if JMH exits non-zero or if zero benchmarks match the filter.
     */
    static int printSelectedBenchmarks(Path jar, String benchmarkArgs, Logger logger) {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator
                + (Hash4jCheckout.isWindows() ? "java.exe" : "java");

        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.add("-jar");
        cmd.add(jar.toString());
        if (benchmarkArgs != null && !benchmarkArgs.isBlank()) {
            cmd.addAll(tokenize(benchmarkArgs));
        }
        // -lp prints every @Benchmark × @Param combination JMH would execute, without
        // invoking @Setup or @Benchmark. We avoid ProcessBuilder.inheritIO because the
        // Gradle daemon's output forwarder turns ~2000 lines of -lp output into a 90 s
        // crawl; capturing into a buffer and emitting via the logger once is fast.
        cmd.add("-lp");

        String output;
        int exit;
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(5, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new GradleException("JMH listing did not finish within 5 minutes.");
            }
            exit = p.exitValue();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new GradleException("Could not run JMH listing: " + e.getMessage(), e);
        }

        logger.lifecycle("Benchmarks that will be executed on EC2 (resolved via JMH -lp):");
        logger.lifecycle(output);

        if (exit != 0) {
            throw new GradleException(
                    "JMH listing failed (exit " + exit + "). Likely cause: malformed "
                            + "benchmark_args. Fix the configuration and retry.");
        }

        int count = countBenchmarks(output);
        if (count == 0) {
            throw new GradleException(
                    "benchmark_args matches zero benchmarks. Refine the filter and retry.");
        }
        logger.lifecycle("{} benchmark(s) selected.", count);
        return count;
    }

    static int countBenchmarks(String lpOutput) {
        int count = 0;
        for (String line : lpOutput.split("\\R")) {
            if (line.isEmpty() || Character.isWhitespace(line.charAt(0))) continue;
            if (line.strip().equals("Benchmarks:")) continue;
            count++;
        }
        return count;
    }

    static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inDouble = false;
        boolean inSingle = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (Character.isWhitespace(c) && !inDouble && !inSingle) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }
}
