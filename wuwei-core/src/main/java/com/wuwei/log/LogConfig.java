package com.wuwei.log;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Log file utilities for the frontend LogViewer.
 * Actual logging is handled by Spring Boot's Logback.
 */
public class LogConfig {

    private static final Path LOGS = Paths.get(System.getProperty("user.home"), ".wuwei", "logs");
    public static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    public static void init() {
        // Spring Boot Logback handles logging now — just ensure dirs exist
        try {
            Files.createDirectories(LOGS.resolve("kernel"));
            Files.createDirectories(LOGS.resolve("render"));
        } catch (IOException e) {
            System.err.println("Log dir init failed: " + e.getMessage());
        }
    }

    public static Path logDir() { return LOGS; }
    public static String today() { return LocalDate.now().format(DATE_FMT); }

    public static List<String> listDates(String source) {
        Path dir = LOGS.resolve(source);
        if (!Files.isDirectory(dir)) return List.of();
        try (var s = Files.list(dir)) {
            return s.filter(Files::isRegularFile)
                .map(p -> p.getFileName().toString().replace(".log", ""))
                .sorted(Comparator.reverseOrder())
                .collect(Collectors.toList());
        } catch (IOException e) { return List.of(); }
    }

    public static String readLog(String source, String date) {
        Path f = LOGS.resolve(source).resolve(date + ".log");
        if (!Files.exists(f)) return "";
        try { return Files.readString(f); } catch (IOException e) { return "Error: " + e.getMessage(); }
    }

    public static void logRender(String level, String msg) {
        try {
            Path f = LOGS.resolve("render").resolve(today() + ".log");
            Files.createDirectories(f.getParent());
            String line = LocalTime.now().format(TIME_FMT) + " [" + level + "] " + msg + "\n";
            Files.writeString(f, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }
}
