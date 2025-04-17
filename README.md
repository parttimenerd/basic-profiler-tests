CPU Time Sampler Tester
=======================

Basic tester for the [CPU Time Sampler](https://github.com/openjdk/jdk/pull/20752), that runs JFR in different configurations,
with different GCs with the [renaissance suite](https://renaissance.dev/).

This will take long but emits a csv file (default `results.csv`) with the results
and helps to check if the CPU Time Sampler is working as expected, i.e. not crashing
or producing less events than the normal JFR sampler.

Features
--------
- Profile with different JFR chunk sizes
- ... different heap sizes
- ... different GCs
- ... different samplers (the standard JFR and the CPU Time Sampler)
- ... different JFR recording durations
- ... different renaissance iteration numbers

This allows you to stress test the samplers.

Usage
-----
This requires that `java` is actually a build from https://github.com/openjdk/jdk/pull/20752
(https://github.com/parttimenerd/jdk/tree/parttimenerd_cooperative_cpu_time_sampler or 
https://github.com/parttimenerd/jdk/tree/parttimenerd_jfr_cpu_time_sampler4)

```bash
mvn package
java -jar target/basic-profiler-tests.jar
```

Options via `--help`:

```sh
Usage: ctest [-ahvV] [--keep-jfr] [--csv-file=<csvFile>] [-i=<iterations>]
             [--java=<java>] [--jfr-folder=<jfrFolder>] [--runs=<runs>]
             [-b=<benchmarks>[,<benchmarks>...]]... [-d=<jfrDurations>]...
             [-g=<gcs>[,<gcs>...]]... [-H=<heapSizes>[,<heapSizes>...]]...
             [-m=<maxChunkSizes>[,<maxChunkSizes>...]]... [-s=<samplers>[,
             <samplers>...]]...
Starts a JFR recording and tests it with different scenarios.
  -a, --append-csv           Append to the CSV file instead of overwriting it.
  -b, --benchmark=<benchmarks>[,<benchmarks>...]
                             The benchmarks to run. Possible values: RENAISSANCE
      --csv-file=<csvFile>   The output file to write the results to.
  -d, --durations=<jfrDurations>
                             Duration of the recordings, recordings will be
                               repeated till the benchmark ends. Possible
                               values: FULL, SHORT, MEDIUM, TINY, RANDOM,
                               RANDOM_SHORT
  -g, --gcs=<gcs>[,<gcs>...] The garbage collectors to use. Possible values:
                               G1, ZGC, SERIAL, PARALLEL
  -h, --help                 Show this help message and exit.
  -H, --heap-sizes=<heapSizes>[,<heapSizes>...]
                             The heap sizes to use. Possible values: DEFAULT
  -i, --iterations=<iterations>
                             The number of iterations to run the benchmarks
                               (for renaisance and dacapo, -1 for default).
      --java=<java>          The java executable to use.
      --jfr-folder=<jfrFolder>
                             The folder to write the JFR files to.
      --keep-jfr             The JFR file to write the recordings to.
  -m, --max-chunk-sizes=<maxChunkSizes>[,<maxChunkSizes>...]
                             The max chunk sizes to use. Possible values:
                               ONE_MB, DEFAULT
      --runs=<runs>          The number of runs of the whole suite, -1 for
                               infinite runs.
  -s, --samplers=<samplers>[,<samplers>...]
                             The sampler configs to use. Possible values:
                               CPU_ONLY, OTHER_SAMPLER, WITH_OTHER_SAMPLER,
                               FULL_PROFILE
  -v, --verbose              Print all program outputs
  -V, --version              Print version information and exit.

```


License
-------
GPLv2, Copyright 2025 SAP SE or an SAP affiliate company, Johannes Bechberger and contributors