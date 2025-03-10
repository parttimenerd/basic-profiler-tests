CPU Time Sampler Tester
=======================

Basic tester for the [CPU Time Sampler](https://github.com/openjdk/jdk/pull/20752), that runs JFR in different configurations,
with different GCs with the [renaissance suite](https://renaissance.dev/).

This will take long but emits a csv file (default `results.csv`) with the results
and helps to check if the CPU Time Sampler is working as expected, i.e. not crashing
or producing less events than the normal JFR sampler.

Usage
-----
This requires that `java` is actually a build from https://github.com/openjdk/jdk/pull/20752.

```bash
mvn package
java -jar target/basic-profiler-tests.jar
```

Options via `--help`.

TODO
----
- stop start JFR recordings often

License
-------
GPLv2, Copyright 2025 SAP SE or an SAP affiliate company, Johannes Bechberger and contributors