package me.bechberger.ctest;

import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static picocli.CommandLine.Command;
import static picocli.CommandLine.Option;

@Command(name = "ctest", mixinStandardHelpOptions = true, version = "1.0",
        description = "Starts a JFR recording and tests it with different scenarios.")
public class Main implements Runnable {

    static class JavaOptions {
        private final List<String> options = new ArrayList<>();
        private final List<String> jfrOptions = new ArrayList<>();
        private final List<String> jfrRecorderOptions = new ArrayList<>();

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
            allOptions.add("-XX:StartFlightRecording=" + toJFROptions(jfrFile));
            allOptions.addAll(options);
            return allOptions;
        }

        String toJFROptions(Path jfrFile) {
            return "filename=" + jfrFile + "," + String.join(",", jfrOptions);
        }
    }

    interface OptionAdder {
        void addOption(JavaOptions options);
    }

    interface CSVValue {
        String toCSVValue();
    }

    enum Benchmark implements CSVValue {
        RENAISSANCE(BenchmarkRunner.RenaissanceBenchmarkRunner::new),
        ;
        // DACAPO,
        // CLASSUNLOAD(BenchmarkRunner.ClassUnloadTestRunner::new);

        private final BiFunction<OptionSet, Integer, BenchmarkRunner> creator;

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

    private static final String CPU_TIME_SAMPLE_CONFIG = "jdk.CPUTimeSample#enabled=true,jdk.CPUTimeSample#throttle=1ms";
    private static final String STANDARD_JFR_SAMPLE_CONFIG = "jdk.ExecutionSample#enabled=true,jdk.ExecutionSample#period=1ms,jdk.NativeMethodSample#enabled=true,jdk.NativeMethodSample#period=1ms";

    enum Sampler implements OptionAdder, CSVValue {
        CPU_ONLY(CPU_TIME_SAMPLE_CONFIG),
        OTHER_SAMPLER(STANDARD_JFR_SAMPLE_CONFIG),
        WITH_OTHER_SAMPLER(CPU_TIME_SAMPLE_CONFIG, STANDARD_JFR_SAMPLE_CONFIG),
        FULL_PROFILE("settings=profile.jfc", CPU_TIME_SAMPLE_CONFIG, STANDARD_JFR_SAMPLE_CONFIG);


        public final String config;

        Sampler(String... config) {
            this.config = String.join(",", config);
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
        // SHENANDOAH("ShenandoahGC"),
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
        ;
        //ONE_GB("1g"),;

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

    enum JFRDuration {
        FULL(() -> Long.MAX_VALUE),
        SHORT(() -> 10_000L),
        MEDIUM(() -> 60_000L),
        TINY(() -> 1_000L),
        RANDOM(() -> (long) (Math.random() * 60_000)),
        RANDOM_SHORT(() -> (long) (Math.random() * 10_000));

        private final Supplier<Long> durationMillis;

        JFRDuration(Supplier<Long> durationMillis) {
            this.durationMillis = durationMillis;
        }

        long getNextDurationMillis() {
            return durationMillis.get();
        }

        boolean producesMultipleFiles() {
            return this != FULL;
        }
    }

    @Option(names = {"-d", "--durations"}, description = "Duration of the recordings, recordings will be repeated till the benchmark ends. Possible values: ${COMPLETION-CANDIDATES}")
    List<JFRDuration> jfrDurations = List.of(JFRDuration.values());

    record OptionSet(Benchmark benchmark, Sampler sampler, GC gc, MaxChunkSize maxChunkSize, HeapSize heapSize,
                     JFRDuration duration, boolean randomizeOrder) implements OptionAdder {
        @Override
        public void addOption(JavaOptions options) {
            sampler.addOption(options);
            gc.addOption(options);
            maxChunkSize.addOption(options);
            heapSize.addOption(options);
        }

        public List<String> toCSV() {
            return List.of(benchmark.toCSVValue(), sampler.toCSVValue(), gc.toCSVValue(), maxChunkSize.toCSVValue(), heapSize.toCSVValue(), duration.name().toLowerCase());
        }

        public static List<String> toCSVHeader() {
            return List.of("benchmark", "sampler", "gc", "max chunk size", "heap size", "duration");
        }
    }

    /**
     * '
     * Generates all possible combinations of the options
     *
     * @return a stream of all possible option sets
     */
    Stream<OptionSet> optionSets() {
        Supplier<Stream<OptionSet>> optionSetStream = () -> jfrDurations.stream().flatMap(d -> benchmarks.stream().flatMap(benchmark ->
                samplers.stream().flatMap(sampler ->
                        gcs.stream().flatMap(gc ->
                                maxChunkSizes.stream().flatMap(maxChunkSize ->
                                        heapSizes.stream().map(heapSize ->
                                                new OptionSet(benchmark, sampler, gc, maxChunkSize, heapSize, d, randomBenchmarkOrder)))))));
        return IntStream.range(0, runs == -1 ? Integer.MAX_VALUE : runs).mapToObj(i -> {
            if (randomConfigOrder) {
                var optionSetList = optionSetStream.get().collect(Collectors.toCollection(ArrayList::new));
                Collections.shuffle(optionSetList);
                return optionSetList.stream();
            }
            return optionSetStream.get();
        }).flatMap(s -> s);
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

    enum Verbosity {
        SILENT,
        ALL,
        ALL_WITH_TIMESTAMPS
    }

    @Option(names = {"-v", "--verbose"}, description = "Print all program outputs. Possible values: ${COMPLETION-CANDIDATES}")
    Verbosity verbose = Verbosity.SILENT;

    @Option(names = "--java", description = "The java executable to use.")
    String java = "java";

    @Option(names = "--random-benchmark-order", description = "Randomize the order of the renaissance benchmarks")
    boolean randomBenchmarkOrder = false;

    @Option(names = "--random-config-order", description = "Randomize the order of the configs")
    boolean randomConfigOrder = false;

    void run(OptionSet options) {
        var runner = options.benchmark.createRunner(options, iterations);
        try {
            Function<Integer, Path> jfrFileGenerator;
            Path generatedFileOrFolder;
            String prefix = String.join("_", options.toCSV()).replace(' ', '-') + "_" + System.currentTimeMillis();
            if (options.duration.producesMultipleFiles()) {
                var baseFolder = Path.of(jfrFolder, prefix);
                Files.createDirectories(baseFolder);
                jfrFileGenerator = i -> baseFolder.resolve(i + ".jfr");
                generatedFileOrFolder = baseFolder;
            } else {
                jfrFileGenerator = i -> {
                    if (i != 0) {
                        throw new IllegalArgumentException("Only one file expected");
                    }
                    return Path.of(jfrFolder, prefix + ".jfr");
                };
                generatedFileOrFolder = Path.of(jfrFolder, prefix + ".jfr");
            }
            Runnable deleteAction = () -> {
                if (options.duration.producesMultipleFiles()) {
                    try (var dirStream = Files.walk(generatedFileOrFolder)) {
                        dirStream
                                .map(Path::toFile)
                                .sorted(Comparator.reverseOrder())
                                .forEach(File::delete);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                } else {
                    try {
                        Files.deleteIfExists(generatedFileOrFolder);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            };
            var shutdownHook = new Thread(deleteAction);
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            var result = runner.run(jfrFileGenerator, java, verbose);
            if (!keepJfr) {
                deleteAction.run();
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            }
            if (verbose != Verbosity.SILENT) {
                System.out.println("Finished: " + options);
            }
            if (!result.isReasonable()) {
                System.err.println("Result not reasonable: " + result + " for " + options);
            }
            if (result.error()) {
                System.err.println("Error during execution: " + result + " for " + options);
            }
            System.out.println(result.toCSV());
            try {
                Path.of(csvFile).toFile().createNewFile();
            } catch (IOException e) {
            }
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