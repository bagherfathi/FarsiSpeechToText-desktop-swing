package com.example;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

final class AppSettings {
    private static final Properties VALUES = new Properties();
    static {
        try (InputStream input = AppSettings.class.getResourceAsStream("/smtp-defaults.properties")) {
            if (input != null) VALUES.load(new InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException ex) { throw new java.io.UncheckedIOException("Cannot read bundled local SMTP settings", ex); }
        Path path = Path.of(System.getProperty("chalabi.config", "chalabi.properties"));
        if (Files.isRegularFile(path)) {
            try (Reader reader = Files.newBufferedReader(path)) { VALUES.load(reader); }
            catch (IOException ex) { throw new java.io.UncheckedIOException("Cannot read " + path, ex); }
        }
    }
    static String get(String key, String fallback) {
        String value = System.getProperty("chalabi." + key);
        return value == null ? VALUES.getProperty(key, fallback).trim() : value;
    }
    static Path dataDirectory() {
        return Path.of(get("data.dir", Path.of(System.getProperty("user.home"), ".chalabi").toString()));
    }
}
