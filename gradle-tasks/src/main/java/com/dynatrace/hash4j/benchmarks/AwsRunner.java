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
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.BlockDeviceMapping;
import software.amazon.awssdk.services.ec2.model.EbsBlockDevice;
import software.amazon.awssdk.services.ec2.model.IamInstanceProfileSpecification;
import software.amazon.awssdk.services.ec2.model.InstanceNetworkInterfaceSpecification;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse;
import software.amazon.awssdk.services.ec2.model.ShutdownBehavior;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;
import software.amazon.awssdk.services.ec2.model.VolumeType;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetBucketLifecycleConfigurationRequest;
import software.amazon.awssdk.services.s3.model.GetBucketLifecycleConfigurationResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.sts.StsClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

final class AwsRunner implements AutoCloseable {

    private final BenchmarkConfig cfg;
    private final Logger logger;
    private final Region region;
    private S3Client s3;
    private Ec2Client ec2;
    private StsClient sts;

    AwsRunner(BenchmarkConfig cfg, Logger logger) {
        this.cfg = cfg;
        this.logger = logger;
        this.region = Region.of(cfg.region);
    }

    String checkAuth() {
        try {
            var resp = sts().getCallerIdentity();
            return resp.arn();
        } catch (Exception e) {
            throw new GradleException(
                    "AWS credentials are invalid or expired: " + e.getMessage()
                            + "\nRefresh credentials and re-run.", e);
        }
    }

    void warnIfNoLifecycleRule() {
        try {
            GetBucketLifecycleConfigurationResponse resp = s3().getBucketLifecycleConfiguration(
                    GetBucketLifecycleConfigurationRequest.builder().bucket(cfg.bucket).build());
            boolean hasMatchingRule = resp.rules().stream().anyMatch(rule -> {
                if (rule.status() == null || !"Enabled".equalsIgnoreCase(rule.statusAsString())) {
                    return false;
                }
                if (rule.expiration() == null || rule.expiration().days() == null) {
                    return false;
                }
                String prefix = extractRulePrefix(rule);
                return prefix == null || prefix.isEmpty()
                        || cfg.resultsPrefix.startsWith(stripTrailingSlash(prefix))
                        || stripTrailingSlash(prefix).startsWith(cfg.resultsPrefix);
            });
            if (!hasMatchingRule) {
                logger.warn(
                        "WARNING: Bucket {} has no enabled lifecycle expiration rule covering prefix '{}/'. "
                                + "Results will NOT be auto-deleted. Configure a 7-day expiration rule per README.",
                        cfg.bucket, cfg.resultsPrefix);
            }
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                logger.warn(
                        "WARNING: Bucket {} has no lifecycle configuration. "
                                + "Results will NOT be auto-deleted. Configure a 7-day expiration rule per README.",
                        cfg.bucket);
            } else {
                logger.warn("Could not read bucket lifecycle for {}: {}", cfg.bucket, e.getMessage());
            }
        } catch (Exception e) {
            logger.warn("Could not check bucket lifecycle: {}", e.getMessage());
        }
    }

    private static String extractRulePrefix(software.amazon.awssdk.services.s3.model.LifecycleRule rule) {
        if (rule.prefix() != null) {
            return rule.prefix();
        }
        if (rule.filter() != null) {
            if (rule.filter().prefix() != null) return rule.filter().prefix();
            if (rule.filter().and() != null && rule.filter().and().prefix() != null) {
                return rule.filter().and().prefix();
            }
        }
        return null;
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    void uploadInputDir(Path inputDir, String s3KeyPrefix) {
        if (!Files.isDirectory(inputDir)) {
            throw new GradleException("Not a directory: " + inputDir);
        }
        try (Stream<Path> files = Files.list(inputDir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                if (!Files.isRegularFile(f)) continue;
                String key = s3KeyPrefix + "input/" + f.getFileName();
                logger.lifecycle("  uploading s3://{}/{}", cfg.bucket, key);
                s3().putObject(PutObjectRequest.builder()
                                .bucket(cfg.bucket)
                                .key(key)
                                .build(),
                        f);
            }
        } catch (IOException e) {
            throw new GradleException("Could not list " + inputDir, e);
        }
    }

    LaunchResult launchInstance(String userData, String instanceName, String amiId, String instanceType) {
        String userDataB64 = Base64.getEncoder().encodeToString(userData.getBytes(StandardCharsets.UTF_8));

        List<Tag> tags = new ArrayList<>();
        tags.add(Tag.builder().key("Name").value(instanceName).build());
        for (BenchmarkConfig.ExtraTag t : cfg.extraTags) {
            tags.add(Tag.builder().key(t.key).value(t.value).build());
        }

        BlockDeviceMapping rootVolume = BlockDeviceMapping.builder()
                .deviceName("/dev/sda1")
                .ebs(EbsBlockDevice.builder()
                        .volumeSize(cfg.ebsVolumeGb)
                        .volumeType(VolumeType.GP3)
                        .deleteOnTermination(true)
                        .build())
                .build();

        RunInstancesRequest.Builder req = RunInstancesRequest.builder()
                .imageId(amiId)
                .instanceType(instanceType)
                .minCount(1)
                .maxCount(1)
                .keyName(cfg.keyName)
                .iamInstanceProfile(IamInstanceProfileSpecification.builder()
                        .name(cfg.instanceProfile)
                        .build())
                .userData(userDataB64)
                .instanceInitiatedShutdownBehavior(ShutdownBehavior.TERMINATE)
                .blockDeviceMappings(rootVolume)
                .tagSpecifications(
                        TagSpecification.builder()
                                .resourceType("instance")
                                .tags(tags)
                                .build(),
                        TagSpecification.builder()
                                .resourceType("volume")
                                .tags(tags)
                                .build())
                .networkInterfaces(InstanceNetworkInterfaceSpecification.builder()
                        .deviceIndex(0)
                        .associatePublicIpAddress(true)
                        .groups(cfg.securityGroup)
                        .build());

        RunInstancesResponse resp = ec2().runInstances(req.build());
        if (resp.instances().isEmpty()) {
            throw new GradleException("EC2 runInstances returned no instances");
        }
        String instanceId = resp.instances().get(0).instanceId();

        String host = null;
        try {
            var desc = ec2().describeInstances(b -> b.instanceIds(instanceId));
            if (!desc.reservations().isEmpty() && !desc.reservations().get(0).instances().isEmpty()) {
                var inst = desc.reservations().get(0).instances().get(0);
                host = inst.publicDnsName();
                if (host == null || host.isBlank()) {
                    host = inst.publicIpAddress();
                }
            }
        } catch (Exception e) {
            logger.lifecycle("Could not resolve public host yet: {}", e.getMessage());
        }
        return new LaunchResult(instanceId, host);
    }

    private S3Client s3() {
        if (s3 == null) {
            s3 = S3Client.builder().region(region).build();
        }
        return s3;
    }

    private Ec2Client ec2() {
        if (ec2 == null) {
            ec2 = Ec2Client.builder().region(region).build();
        }
        return ec2;
    }

    private StsClient sts() {
        if (sts == null) {
            sts = StsClient.builder().region(region).build();
        }
        return sts;
    }

    @Override
    public void close() {
        if (s3 != null) {
            try { s3.close(); } catch (Exception ignored) {}
        }
        if (ec2 != null) {
            try { ec2.close(); } catch (Exception ignored) {}
        }
        if (sts != null) {
            try { sts.close(); } catch (Exception ignored) {}
        }
    }

    static final class LaunchResult {
        final String instanceId;
        final String host;

        LaunchResult(String instanceId, String host) {
            this.instanceId = instanceId;
            this.host = host;
        }
    }

}
