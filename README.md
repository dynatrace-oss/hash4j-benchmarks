# Benchmark results for hash4j
This repository collects results of [JMH benchmarks](https://github.com/openjdk/jmh) for the [hash4j library](https://github.com/dynatrace-oss/hash4j).

For better reproducibility, the benchmarks are executed on AWS EC2 instances. The JSON files in the `results` folder contain the benchmark results as well as information about used operating system and Java versions. Results are grouped by AWS instance type into subfolders.

## Result layout

Results live under `results/<instance_type>/<git_revision>/<YYYY-MM-DD-HH-MM-SS>/` and contain:
* `jmh-results.json` — JMH JSON output
* `metadata.json` — merged metadata about the run (AWS instance, CPU, kernel, Java, JMH args, jar SHA-256, timestamps)
* `jmh.log` / `runner.log` — full output, kept for triage

Pre-2026 results were originally stored in a flat layout (`<instance_type>/<timestamp> <revision>.json`) and have been migrated to the nested layout; their `metadata.json` only carries the fields recoverable from the original filename (`hash4j.git_revision` and `aws.instance_type`).

For Intel CPU instances, Turbo Boost is switched off (Ubuntu 22.04+; compare [here](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/processor_state_control.html)):

```
sudo sh -c "echo 1 > /sys/devices/system/cpu/intel_pstate/no_turbo"
```

## Running benchmarks with Gradle

Two Gradle tasks drive the full end-to-end workflow. **Run them from the root of this repository** (`hash4j-benchmarks/`), the same place `gradlew`/`gradlew.bat` lives. Use `./gradlew` on macOS/Linux and `.\gradlew.bat` on Windows.

```
# Build the jmh jar and print every @Benchmark × @Param combination that would run
# for the supplied run YAML — useful for iterating on benchmark_args.
./gradlew listBenchmarks -Prun=<path to run YAML>

# Build the jmh jar, list the selected benchmarks (via listBenchmarks), launch an
# EC2 instance, run async. Aborts if benchmark_args matches zero benchmarks.
./gradlew submitBenchmarks -Prun=<path to run YAML>

# When the SNS notification arrives, fetch the results by the S3 key it contains.
# Linux/macOS:
./gradlew fetchResults -Ps3Key="<key from the email>"
# Windows:
.\gradlew.bat fetchResults "-Ps3Key=<key from the email>"
```

`submitBenchmarks` reads the hash4j git ref (`ref`), optional run label (`label`), and optional extra JMH args (`benchmark_args`) from the run YAML passed via `-Prun=`. All other settings (AWS region, bucket, instance type, …) come from `config.yaml` at the project root. It also:
* requires a local checkout of hash4j at the path configured in `hash4j_repo_path` (default: `../hash4j`, i.e. a sibling directory alongside this repo) with the requested ref present (run `git fetch --all --tags` in that repo if not);
* materialises the requested revision into a `git worktree` under `build/hash4j-worktree/` (your main hash4j checkout is untouched);
* builds the JMH jar via `gradlew jmhJar` inside the worktree;
* runs `listBenchmarks` first (declared as a `dependsOn`), which prints every `@Benchmark` × `@Param` combination that would run (via `java -jar … <benchmark_args> -lp`) and the matching count — aborts the whole task (no upload, no instance) if JMH exits non-zero or the filter matches zero benchmarks;
* uploads the jar + a generated `run.sh` cloud-init script to `s3://<bucket>/<key>/input/`;
* launches an EC2 instance that installs Java 25, runs the benchmark, uploads results to `s3://<bucket>/<key>/output/`, sends an SNS email, and terminates itself.

The SNS notification subject reflects the outcome:
* `JMH complete: <name>` — normal completion
* `JMH TIMEOUT: <name>` — the inner watchdog killed the benchmark after `max_runtime_hours`; partial results are uploaded
* `JMH FAILED: <name> (exit=<N>)` — the benchmark exited non-zero; see `jmh.log` in the S3 prefix

`fetchResults` downloads the result + metadata and places them under the nested layout described above. It refuses to overwrite an existing target directory.

## Configuration

Configuration is split between a **global `config.yaml`** at the repo root (account-wide AWS settings) and a **run YAML** passed via `-Prun=` (which hash4j revision to benchmark on what kind of machine, plus optional label and JMH args). The split lets you keep one stable `config.yaml` and check in multiple run YAMLs for different benchmark slices.

* `submitBenchmarks` and `listBenchmarks` require `-Prun=<path>`. They bootstrap whatever is missing — if `config.yaml` does not exist at the repo root it is written from the template; if the run YAML path does not exist it is written from its own template. In either case the task aborts with the location(s) of the file(s) created so you can fill them in and re-run.
* `fetchResults` only needs `config.yaml`; it bootstraps it the same way if missing.

All `*.yaml` files in the repo root are gitignored, so both `config.yaml` and any run YAMLs you keep there stay out of version control.

`config.yaml` — global settings:

```yaml
region: us-east-1
bucket: my-hash4j-benchmarks
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
# Bump if your benchmark needs more disk for flame graphs / heap dumps / logs.
ebs_volume_gb: 30
extra_tags:
  - Key: dt_owner_email
    Value: you@dynatrace.com
```

Run YAML (e.g. `run.yaml`) — what to benchmark and on what machine:

```yaml
# hash4j git ref (sha, tag, or branch) to benchmark. Required.
ref: main
# EC2 instance type to launch.
instance_type: c8i.xlarge
# Ubuntu 26.04 (resolute) AMI for us-east-1 region.
ami_id: ami-091138d0f0d41ff90
# Optional human-readable label. When set it is included in the generated run id
# (hash4j-benchmark[-<label>]-<random>) used as the EC2 Name tag, staging dir,
# SNS subject, and submit.id in metadata.json.
label:
# Optional extra JMH args passed to the benchmark jar.
benchmark_args:
```

## One-time AWS setup

You need an S3 bucket, an IAM role + instance profile, a security group, an SNS topic with at least one subscribed email, and an EC2 key pair. All assumed to live in the same region.

Run the commands below **once**, from any local shell where the [AWS CLI](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html) is installed and configured with credentials that can create these resources (`aws configure` or an SSO profile). The working directory doesn't matter — these calls only hit the AWS API.

Most AWS resources are regional, so the CLI needs to know which region. Either set a default once:

```
aws configure set region us-east-1
```

or pass `--region us-east-1` on every command below. IAM is the only exception — IAM is global and ignores `--region`. The snippets below assume you've set a default.

The commands are line-continuation-free so they paste cleanly into both bash and Windows PowerShell. JSON policies are read from files via `file://` to avoid cross-shell quoting differences (PowerShell handles inline JSON differently from bash and `--policy-document file://...` sidesteps that).

First, write the two JSON policies to files (in whatever directory you're running from):

```
# trust-policy.json
{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}
```

```
# jmh-access.json  (remember to replace <your-bucket-name>)
{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":["s3:GetObject","s3:PutObject","s3:DeleteObject"],"Resource":"arn:aws:s3:::<your-bucket-name>/*"},{"Effect":"Allow","Action":"s3:ListBucket","Resource":"arn:aws:s3:::<your-bucket-name>"},{"Effect":"Allow","Action":"sns:Publish","Resource":"*"}]}
```

Then run, one per line:

```
aws s3api create-bucket --bucket <your-bucket-name>

aws ec2 create-key-pair --key-name <your-key-name> --query KeyMaterial --output text > <your-key-name>.pem
# On Linux/macOS: chmod 400 <your-key-name>.pem ; mv it to ~/.ssh/

aws ec2 create-security-group --group-name hash4j-benchmark-sg --description "Outbound + optional SSH for hash4j benchmark instances"
aws ec2 authorize-security-group-ingress --group-name hash4j-benchmark-sg --protocol tcp --port 22 --cidr 0.0.0.0/0

aws iam create-role --role-name hash4j-benchmark-role --assume-role-policy-document file://trust-policy.json
aws iam put-role-policy --role-name hash4j-benchmark-role --policy-name jmh-access --policy-document file://jmh-access.json
aws iam create-instance-profile --instance-profile-name hash4j-benchmark-profile
aws iam add-role-to-instance-profile --instance-profile-name hash4j-benchmark-profile --role-name hash4j-benchmark-role

aws sns create-topic --name hash4j-benchmark-complete
aws sns subscribe --topic-arn <TopicArn> --protocol email --notification-endpoint <you@example.com>
```

**Crucially**: configure a **7-day expiration lifecycle rule** on the bucket targeting the `runs/` prefix (or whatever you set `results_prefix` to). Write the rule to a file and apply it:

```
# lifecycle.json
{"Rules":[{"ID":"expire-benchmark-runs-after-7d","Status":"Enabled","Filter":{"Prefix":"runs/"},"Expiration":{"Days":7}}]}
```

```
aws s3api put-bucket-lifecycle-configuration --bucket <your-bucket-name> --lifecycle-configuration file://lifecycle.json
```

`submitBenchmarks` performs a soft precheck and logs a warning if no matching rule is found, but it does not fail — set the rule once and forget it.
