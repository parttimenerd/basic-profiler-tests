package me.bechberger.ctest;

import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static picocli.CommandLine.*;

@Command(name = "ctest", mixinStandardHelpOptions = true, version = "1.0",
description = "Starts a JFR recording and tests it with different scenarios.")
public class Main implements Runnable {

    static class JavaOptions {
        private List<String> options = new ArrayList<>();
        private List<String> jfrOptions = new ArrayList<>();
        private List<String> jfrRecorderOptions = new ArrayList<>();

        public void addOption(String option) {
            options.add(option);
        }

        public void addJfrOption(String option) {
            jfrOptions.add(option);
        }

        public void addJfrRecorderOption(String option) {
            jfrRecorderOptions.add(option);
        }

        List<String> toOptions(Path jfrFile) {
            List<String> allOptions = new ArrayList<>();
            if (!jfrRecorderOptions.isEmpty()) {
                allOptions.add("-XX:FlightRecorderOptions=" + String.join(",", jfrRecorderOptions));
            }
            allOptions.add("-XX:+UnlockDiagnosticVMOptions");
            allOptions.add("-XX:+DebugNonSafepoints");
            allOptions.add("-XX:StartFlightRecording=filename=" + jfrFile + "," + String.join(",", jfrOptions));
            allOptions.addAll(options);
            return allOptions;
        }
    }

    interface OptionAdder {
        void addOption(JavaOptions options);
    }

    interface CSVValue {
        String toCSVValue();
    }

    enum Benchmark implements CSVValue {
        RENAISSANCE(BenchmarkRunner.RenaissanceBenchmarkRunner::new),;
       // DACAPO,
       // CLASSUNLOAD(BenchmarkRunner.ClassUnloadTestRunner::new);

        private BiFunction<OptionSet, Integer, BenchmarkRunner> creator;

        Benchmark(BiFunction<OptionSet, Integer, BenchmarkRunner> creator) {
            this.creator = creator;
        }

        @Override
        public String toCSVValue() {
            return name().toLowerCase();
        }

        public BenchmarkRunner createRunner(OptionSet options, int iterations) {
            return creator.apply(options, iterations);
        }
    }

    @Option(names = {"-b", "--benchmark"}, description = "The benchmarks to run. Possible values: ${COMPLETION-CANDIDATES}", split = ",")
    List<Benchmark> benchmarks = List.of(Benchmark.values());

    enum Sampler implements OptionAdder, CSVValue {
        CPU_ONLY("jdk.CPUTimeSample#enabled=true,jdk.CPUTimeSample#throttle=1ms"),
        WITH_OTHER_SAMPLER("jdk.CPUTimeSample#enabled=true,jdk.CPUTimeSample#throttle=1ms,jdk.ExecutionSample#enabled=true,jdk.ExecutionSample#period=1ms,jdk.NativeMethodSample#enabled=true,jdk.NativeMethodSample#period=1ms"),
        FULL_PROFILE("settings=profile.jfc,jdk.CPUTimeSample#enabled=true,jdk.CPUTimeSample#throttle=1ms,jdk.ExecutionSample#enabled=true,jdk.ExecutionSample#period=1ms,jdk.NativeMethodSample#enabled=true,jdk.NativeMethodSample#period=1ms");

        public final String config;

        Sampler(String config) {
            this.config = config;
        }

        @Override
        public void addOption(JavaOptions options) {
            options.addJfrOption(config);
        }

        @Override
        public String toCSVValue() {
            return name().toLowerCase().replace("_", " ");
        }
    }

    @Option(names = {"-s", "--samplers"}, description = "The sampler configs to use. Possible values: ${COMPLETION-CANDIDATES}", split = ",")
    List<Sampler> samplers = List.of(Sampler.values());

    enum GC implements OptionAdder, CSVValue {
        G1("G1GC"),
        ZGC("ZGC"),
        SHENANDOAH("ShenandoahGC"),
        SERIAL("SerialGC"),
        PARALLEL("ParallelGC");

        public final String name;

        GC(String name) {
            this.name = name;
        }

        public String toOption() {
            return "-XX:+Use" + name;
        }

        @Override
        public void addOption(JavaOptions options) {
            options.addOption(toOption());
        }

        @Override
        public String toCSVValue() {
            return name;
        }
    }

    @Option(names = {"-g", "--gcs"}, description = "The garbage collectors to use. Possible values: ${COMPLETION-CANDIDATES}", split = ",")
    List<GC> gcs = List.of(GC.values());

    enum MaxChunkSize implements OptionAdder, CSVValue {
        ONE_MB("1MB"),
        DEFAULT("12MB");

        public final String size;

        MaxChunkSize(String size) {
            this.size = size;
        }

        @Override
        public void addOption(JavaOptions options) {
            options.addJfrRecorderOption("maxchunksize=" + size);
        }

        @Override
        public String toCSVValue() {
            return size;
        }
    }

    @Option(names = {"-m", "--max-chunk-sizes"}, description = "The max chunk sizes to use. Possible values: ${COMPLETION-CANDIDATES}", split = ",")
    List<MaxChunkSize> maxChunkSizes = List.of(MaxChunkSize.values());

    enum HeapSize implements OptionAdder {
        DEFAULT(""),
        ONE_GB("1g"),;

        public final String size;

        HeapSize(String size) {
            this.size = size;
        }

        @Override
        public void addOption(JavaOptions options) {
            if (!size.isEmpty()) {
                options.addOption("-Xmx" + size);
            }
        }

        public String toCSVValue() {
            return size;
        }
    }

    @Option(names = {"-H", "--heap-sizes"}, description = "The heap sizes to use. Possible values: ${COMPLETION-CANDIDATES}", split = ",")
    List<HeapSize> heapSizes = List.of(HeapSize.values());

    record OptionSet(Benchmark benchmark, Sampler sampler, GC gc, MaxChunkSize maxChunkSize, HeapSize heapSize) implements OptionAdder {
        @Override
        public void addOption(JavaOptions options) {
            sampler.addOption(options);
            gc.addOption(options);
            maxChunkSize.addOption(options);
            heapSize.addOption(options);
        }

        public List<String> toCSV() {
            return List.of(benchmark.toCSVValue(), sampler.toCSVValue(), gc.toCSVValue(), maxChunkSize.toCSVValue(), heapSize.toCSVValue());
        }

        public static List<String> toCSVHeader() {
            return List.of("benchmark", "sampler", "gc", "max chunk size", "heap size");
        }
    }

    /**'
     * Generates all possible combinations of the options
     * @return a stream of all possible option sets
     */
    Stream<OptionSet> optionSets() {
        Stream<OptionSet> optionSetStream = benchmarks.stream().flatMap(benchmark ->
                samplers.stream().flatMap(sampler ->
                        gcs.stream().flatMap(gc ->
                                maxChunkSizes.stream().flatMap(maxChunkSize ->
                                        heapSizes.stream().map(heapSize ->
                                                new OptionSet(benchmark, sampler, gc, maxChunkSize, heapSize))))));
        return IntStream.range(0, runs == -1 ? Integer.MAX_VALUE : runs).mapToObj(i -> optionSetStream).flatMap(s -> s);
    }

    @Option(names = {"-i", "--iterations"}, description = "The number of iterations to run the benchmarks (for renaisance and dacapo, -1 for default).")
    int iterations = 1;

    @Option(names = "--runs", description = "The number of runs of the whole suite, -1 for infinite runs.")
    int runs = 1;

    @Option(names = "--csv-file", description = "The output file to write the results to.")
    String csvFile = "results.csv";

    @Option(names = "--keep-jfr", description = "The JFR file to write the recordings to.")
    boolean keepJfr = false;

    @Option(names = "--jfr-folder", description = "The folder to write the JFR files to.")
    String jfrFolder = "jfr";

    @Option(names = {"-a", "--append-csv"}, description = "Append to the CSV file instead of overwriting it.")
    boolean appendCsv = false;

    @Option(names = {"-v", "--verbose"}, description = "Print all program outputs")
    boolean verbose = false;

    @Option(names = "--java", description = "The java executable to use.")
    String java = "java";

    void run(OptionSet options) {
        var runner = options.benchmark.createRunner(options, iterations);
        try {
            var jfrFile = Path.of(jfrFolder, String.join("_", options.toCSV()) + "_" + System.currentTimeMillis() + ".jfr");
            var result = runner.run(jfrFile, java, verbose);
            if (!keepJfr) {
                Files.deleteIfExists(jfrFile);
            }
            if (verbose) {
                System.out.println("Finished: " + options);
            }
            if (!result.isReasonable()) {
                System.err.println("Result not reasonable: " + result + " for " + options);
            }
            if (result.error()) {
                System.err.println("Error during execution: " + result + " for " + options);
            }
            System.out.println(result.toCSV());
            try (var s = Files.newOutputStream(Path.of(csvFile), StandardOpenOption.APPEND)) {
                s.write((String.join(",", result.toCSV()) + "\n").getBytes());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    void setup() {
        Path jfrFolder = Path.of(this.jfrFolder);
        if (!jfrFolder.toFile().exists()) {
            jfrFolder.toFile().mkdir();
        }
        try {
            if (Files.exists(Path.of(csvFile))) {
                if (!appendCsv) {
                    Files.deleteIfExists(Path.of(csvFile));
                } else if (Files.readAllLines(Path.of(csvFile)).isEmpty()) {
                    Files.writeString(Path.of(csvFile), String.join(",", BenchmarkRunner.Result.toCSVHeader()) + "\n");
                }
            } else {
                Files.createFile(Path.of(csvFile));
                Files.writeString(Path.of(csvFile), String.join(",", BenchmarkRunner.Result.toCSVHeader()) + "\n");
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void run() {
        setup();
        optionSets().forEach(this::run);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}