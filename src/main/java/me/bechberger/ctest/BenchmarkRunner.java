package me.bechberger.ctest;

import jdk.jfr.consumer.RecordingFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public abstract class BenchmarkRunner {

    final Main.OptionSet options;
    final int iterations;

    BenchmarkRunner(Main.OptionSet options, int iterations) {
        this.options = options;
        this.iterations = iterations;
    }

    Path downloadIfNeeded(String jarName, String url) {
        // download benchmark if not already present
        Path jarPath = Paths.get(jarName);
        if (!jarPath.toFile().exists()) {
            try {
                new ProcessBuilder("curl", "-L", "-o", jarName, url).inheritIO().start().waitFor();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return jarPath;
    }

    abstract void addOptions(Main.JavaOptions options, Path tmpFolder);

    record Result(Main.OptionSet options, long duration, int otherSamplerEvents, int validCpuTimeEvents, int overflowedCpuTimeEvents, int emptyCpuTimeEvents, boolean error) {

        public List<String> toCSV() {
            List<String> csv = new ArrayList<>(options.toCSV());
            csv.addAll(List.of(String.valueOf(duration), String.valueOf(otherSamplerEvents), String.valueOf(validCpuTimeEvents), String.valueOf(overflowedCpuTimeEvents), String.valueOf(emptyCpuTimeEvents), String.valueOf(isReasonable()), String.valueOf(error)));
            return csv;
        }

        public static List<String> toCSVHeader() {
            List<String> csv = new ArrayList<>(Main.OptionSet.toCSVHeader());
            csv.addAll(List.of("duration", "other sampler events", "valid cpu time events", "overflowed cpu time events", "empty cpu time events", "reasonable", "error"));
            return csv;
        }

        public boolean isReasonable() {
            if (duration <= 0 || validCpuTimeEvents < 100) {
                return false;
            }
            double overflowRate = (double) overflowedCpuTimeEvents / validCpuTimeEvents;
            double errorRate = (double) emptyCpuTimeEvents / validCpuTimeEvents;
            double validRate = (double) validCpuTimeEvents / (validCpuTimeEvents + overflowedCpuTimeEvents + emptyCpuTimeEvents);

            return overflowRate < 0.1 && errorRate < 0.2 && validRate > 0.8;
        }
    }

    Result parseJFRFile(Path jfrFile, Main.OptionSet options, long duration) {
        if (!Files.exists(jfrFile)) {
            return new Result(options, 0, 0, 0, 0, 0, true);
        }
        int otherSamplerEvents = 0;
        int validCpuTimeEvents = 0;
        int overflowedCpuTimeEvents = 0;
        int emptyCpuTimeEvents = 0;
        try {
            for (var event : RecordingFile.readAllEvents(jfrFile)) {
                if (event.getEventType().getName().equals("jdk.CPUTimeSample")) {
                    if (event.getStackTrace() != null && !event.getStackTrace().getFrames().isEmpty()) {
                        validCpuTimeEvents++;
                    } else {
                        emptyCpuTimeEvents++;
                    }
                } else if (event.getEventType().getName().equals("jdk.NativeMethodSample") || event.getEventType().getName().equals("jdk.ExecutionSample")) {
                    otherSamplerEvents++;
                } else if (event.getEventType().getName().equals("jdk.CPUTimeSampleLoss")) {
                    overflowedCpuTimeEvents += event.getInt("lostSamples");
                }
            }
            return new Result(options, duration, otherSamplerEvents, validCpuTimeEvents, overflowedCpuTimeEvents, emptyCpuTimeEvents, false);
        } catch (IOException e) {
            return new Result(options, duration, otherSamplerEvents, validCpuTimeEvents, overflowedCpuTimeEvents, emptyCpuTimeEvents, true);
        }
    }

    String resolveJavaBinary(String javaBinary) {
        if (javaBinary.equals("java")) {
            return System.getProperty("java.home") + "/bin/java";
        }
        return javaBinary;
    }

    Result run(Path jfrFile, String javaBinary, boolean verbose) {
        System.out.println("Running " + options);
        Path tmpFolder = null;
        try {
            tmpFolder = Files.createTempDirectory("jfr");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        Main.JavaOptions javaOptions = new Main.JavaOptions();
        options.addOption(javaOptions);
        addOptions(javaOptions, tmpFolder);
        List<String> command = new ArrayList<>();
        command.add(resolveJavaBinary(javaBinary));
        command.addAll(javaOptions.toOptions(jfrFile));
        System.out.println("Command: " + String.join(" ", command));
        ProcessBuilder pb = new ProcessBuilder(command.toArray(new String[0]));
        if (verbose) {
            pb.inheritIO();
            pb.redirectErrorStream(true);
        }
        long start = System.currentTimeMillis();
        try {
            Process p = pb.start();
            if (p.waitFor() != 0) {
                throw new IOException("Process failed");
            }
            try (var dirStream = Files.walk(tmpFolder)) {
                dirStream
                        .map(Path::toFile)
                        .sorted(Comparator.reverseOrder())
                        .forEach(File::delete);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return parseJFRFile(jfrFile, options, (System.currentTimeMillis() - start) / 1000);
        } catch (IOException | InterruptedException e) {
            return new Result(options, (System.currentTimeMillis() - start) / 1000, 0, 0, 0, 0, true);
        }

    }

    static class RenaissanceBenchmarkRunner extends BenchmarkRunner {

        public RenaissanceBenchmarkRunner(Main.OptionSet options, int iterations) {
            super(options, iterations);
        }

        @Override
        void addOptions(Main.JavaOptions javaOptions, Path tmpFolder) {
            javaOptions.addOption("-jar");
            javaOptions.addOption(downloadIfNeeded("renaissance.jar", "https://github.com/renaissance-benchmarks/renaissance/releases/download/v0.16.0/renaissance-gpl-0.16.0.jar").toString());
            javaOptions.addOption("all");
            if (iterations != -1) {
                javaOptions.addOption("-r");
                javaOptions.addOption(String.valueOf(iterations));
            }
            javaOptions.addOption("--scratch-base");
            javaOptions.addOption(tmpFolder.toString());
        }
    }
}
