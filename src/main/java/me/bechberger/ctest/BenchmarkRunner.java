package me.bechberger.ctest;

import jdk.jfr.consumer.RecordingFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Stream;

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

    record Result(Main.OptionSet options, long duration, int otherSamplerEvents, int validCpuTimeEvents,
                  int overflowedCpuTimeEvents, int emptyCpuTimeEvents, boolean error) {

        public List<String> toCSV() {
            List<String> csv = new ArrayList<>(options.toCSV());
            csv.addAll(Stream.of(duration, otherSamplerEvents, validCpuTimeEvents, overflowedCpuTimeEvents, emptyCpuTimeEvents, isReasonable(), error).map(Object::toString).toList());
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

            return overflowRate < 0.2 && errorRate < 0.2 && validRate > 0.7;
        }
    }

    Result parseJFRFiles(List<Path> jfrFiles, Main.OptionSet options, long duration) {
        int otherSamplerEvents = 0;
        int validCpuTimeEvents = 0;
        int overflowedCpuTimeEvents = 0;
        int emptyCpuTimeEvents = 0;
        for (var jfrFile : jfrFiles) {
            var result = parseJFRFile(jfrFile, options, duration);
            if (result.error) {
                return new Result(options, duration, 0, 0, 0, 0, true);
            }
            otherSamplerEvents += result.otherSamplerEvents;
            validCpuTimeEvents += result.validCpuTimeEvents;
            overflowedCpuTimeEvents += result.overflowedCpuTimeEvents;
            emptyCpuTimeEvents += result.emptyCpuTimeEvents;
        }
        return new Result(options, duration, otherSamplerEvents, validCpuTimeEvents, overflowedCpuTimeEvents, emptyCpuTimeEvents, false);
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

    static class JFRStartAndStopLoop implements Runnable {
        private final Main.OptionSet options;
        private final Main.JavaOptions javaOptions;
        private final Function<Integer, Path> jfrFileGenerator;
        private final CopyOnWriteArrayList<Path> jfrFiles;
        private final long pid;

        JFRStartAndStopLoop(Main.OptionSet options, Main.JavaOptions javaOptions, Function<Integer, Path> jfrFileGenerator, CopyOnWriteArrayList<Path> jfrFiles, long pid) {
            this.options = options;
            this.javaOptions = javaOptions;
            this.jfrFileGenerator = jfrFileGenerator;
            this.jfrFiles = jfrFiles;
            this.pid = pid;
        }

        void jcmd(String... args) {
            List<String> arguments = Arrays.stream(args).flatMap(s -> Stream.of(s.split(","))).toList();
            List<String> command = new ArrayList<>();
            command.add(System.getProperty("java.home") + "/bin/jcmd");
            command.add(String.valueOf(pid));
            command.addAll(arguments);
            try {
                int exit = new ProcessBuilder(command).start().waitFor();
                if (exit != 0) {
                    throw new IOException("jcmd failed: " + String.join(" ", command));
                }
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void run() {
            while (true) {
                try {
                    Thread.sleep(options.duration().getNextDurationMillis());
                    int index = jfrFiles.size();
                    Path jfrFile = jfrFileGenerator.apply(index).toAbsolutePath();
                    jcmd("JFR.stop", index == 0 ? "name=1" : ("name=" + index + "s"), "filename=" + jfrFile);
                    jfrFiles.add(jfrFile);
                    jcmd("JFR.start", "name=" + (index + 1) + "s", javaOptions.toJFROptions(jfrFileGenerator.apply(index + 1).toAbsolutePath()));
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }

    Result run(Function<Integer, Path> jfrFileGenerator, String javaBinary, boolean verbose) {
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
        command.addAll(javaOptions.toOptions(jfrFileGenerator.apply(0)));
        System.out.println("Command: " + String.join(" ", command));
        ProcessBuilder pb = new ProcessBuilder(command.toArray(new String[0]));
        if (verbose) {
            pb.inheritIO();
            pb.redirectErrorStream(true);
        }
        long start = System.currentTimeMillis();
        var jfrFiles = new CopyOnWriteArrayList<Path>();
        try {
            Process p = pb.start();
            int exitCode;
            if (options.duration().producesMultipleFiles()) {
                var starter = new JFRStartAndStopLoop(options, javaOptions, jfrFileGenerator, jfrFiles, p.pid());
                var thread = new Thread(starter);
                thread.start();
                exitCode = p.waitFor();
                thread.interrupt();
                while (thread.isAlive()) {
                    Thread.sleep(100);
                }
            } else {
                jfrFiles.add(jfrFileGenerator.apply(0));
                exitCode = p.waitFor();
            }
            if (exitCode != 0) {
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
            return parseJFRFiles(jfrFiles, options, (System.currentTimeMillis() - start) / 1000);
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
