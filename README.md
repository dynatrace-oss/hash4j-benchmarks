# Benchmark results for hash4j
This repository collects results of [JMH benchmarks](https://github.com/openjdk/jmh) for the [hash4j library](https://github.com/dynatrace-oss/hash4j). 

For better reproducibility, the benchmarks are executed on AWS bare-metal instances. The JSON files in the `result` folder contain the benchmark results as well as information about used operating system and Java versions. The results are grouped by AWS instance into subfolders. The name of the result files contain execution start and the hash4j revision. Turbo Boost is switched off for instances with Intel CPUs, which is possible under Ubuntu 22.04 LTS with (compare [here](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/processor_state_control.html)):

```
sudo sh -c "echo 1 > /sys/devices/system/cpu/intel_pstate/no_turbo"
```

The results are obtained by checking out the corresponding hash4j revision and executing the following command in the base folder of the hash4j project:
```
./gradlew jmh
```
