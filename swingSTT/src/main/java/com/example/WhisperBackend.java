package com.example;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

final class WhisperBackend {
    static List<String> command(Path wav, Path output) {
        String executable = AppSettings.get("whisper.executable", "");
        String model = AppSettings.get("whisper.model", "");
        if (executable.isBlank() || model.isBlank())
            throw new IllegalStateException("Set whisper.executable and whisper.model in chalabi.properties");
        return List.of(executable, "-m", model, "-f", wav.toAbsolutePath().toString(),
                "-l", "fa", "-otxt", "-of", output.toAbsolutePath().toString(), "-nt");
    }
    static String transcribe(Path wav) throws IOException, InterruptedException {
        Path dir = Files.createTempDirectory("chalabi-whisper-");
        Path output = dir.resolve("result");
        Path log = dir.resolve("process.log");
        Process process = null;
        try {
            process = new ProcessBuilder(command(wav, output)).redirectErrorStream(true)
                    .redirectOutput(log.toFile()).start();
            long seconds = Long.parseLong(AppSettings.get("whisper.timeout.seconds", "1800"));
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor();
                throw new IOException("Whisper timed out");
            }
            if (process.exitValue() != 0 || !Files.exists(Path.of(output + ".txt")))
                throw new IOException("Whisper failed (exit " + process.exitValue() + "). Check executable and model compatibility.");
            return Files.readString(Path.of(output + ".txt")).trim();
        } finally {
            if (process != null && process.isAlive()) { process.destroyForcibly(); process.waitFor(); }
            Files.deleteIfExists(Path.of(output + ".txt"));
            Files.deleteIfExists(log);
            Files.deleteIfExists(dir);
        }
    }
}
