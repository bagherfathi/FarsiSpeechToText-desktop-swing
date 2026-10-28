package com.example;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

final class MediaConverter {
    static Path convert(Path media) throws IOException, InterruptedException {
        Path dir = Files.createTempDirectory("chalabi-media-");
        Path wav = dir.resolve("converted.wav");
        Path log = dir.resolve("ffmpeg.log");
        Process process = null;
        boolean success = false;
        try {
            process = new ProcessBuilder(executable(), "-nostdin", "-y", "-loglevel", "error",
                    "-i", media.toAbsolutePath().toString(), "-vn", "-ar", "16000", "-ac", "1",
                    "-acodec", "pcm_s16le", "-f", "wav", wav.toString())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            long timeout = Long.parseLong(AppSettings.get("ffmpeg.timeout.seconds", "1800"));
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor();
                throw new IOException("Media conversion timed out");
            }
            if (process.exitValue() != 0) throw new IOException("FFmpeg conversion failed (exit " + process.exitValue() + ")");
            success = true;
            return wav;
        } finally {
            if (process != null && process.isAlive()) { process.destroyForcibly(); process.waitFor(); }
            Files.deleteIfExists(log);
            if (!success) { Files.deleteIfExists(wav); Files.deleteIfExists(dir); }
        }
    }
    private static String executable() {
        String configured = AppSettings.get("ffmpeg.executable", "");
        if (!configured.isBlank()) return configured;
        String name = System.getProperty("os.name").startsWith("Windows") ? "ffmpeg.exe" : "ffmpeg";
        List<Path> roots = new ArrayList<>();
        roots.add(Path.of("."));
        try {
            Path code = Path.of(MediaConverter.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            roots.add(code.getParent()); roots.add(code.getParent().getParent());
        } catch (Exception ignored) { }
        for (Path root : roots) {
            Path path = root.resolve("tools/ffmpeg/bin").resolve(name);
            if (Files.isRegularFile(path)) return path.toAbsolutePath().toString();
        }
        return name;
    }
}
