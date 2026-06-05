package com.wuwei.log;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.logging.*;
import java.util.stream.Collectors;

/**
 * Zero-dependency dated file logging.
 * stdout/stderr → ~/.wuwei/logs/kernel/YYYY-MM-DD.log + console.
 */
public class LogConfig {

    private static final Path LOGS = Paths.get(System.getProperty("user.home"), ".wuwei", "logs");
    public static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public static void init() {
        try {
            Files.createDirectories(LOGS.resolve("kernel"));
            Files.createDirectories(LOGS.resolve("render"));

            Logger root = Logger.getLogger("");
            root.setLevel(Level.ALL);
            for (Handler h : root.getHandlers()) root.removeHandler(h);
            root.addHandler(new DailyFileHandler("kernel"));

            PrintStream origOut = System.out;
            PrintStream origErr = System.err;
            System.setOut(new TeeStream(origOut));
            System.setErr(new TeeStream(origErr));
        } catch (IOException e) {
            System.err.println("Log init failed: " + e.getMessage());
        }
    }

    public static Path logDir() { return LOGS; }
    public static String today() { return LocalDate.now().format(DATE_FMT); }

    public static List<String> listDates(String source) {
        Path dir = LOGS.resolve(source);
        if (!Files.isDirectory(dir)) return List.of();
        try (var s = Files.list(dir)) {
            return s.filter(Files::isRegularFile)
                .map(p -> p.getFileName().toString().replace(".log",""))
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
            String line = java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")) + " [" + level + "] " + msg + "\n";
            Files.writeString(f, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }

    // ── Internal ──

    static class DailyFileHandler extends StreamHandler {
        private final String category;
        private volatile String currentDate;
        DailyFileHandler(String c) {
            category = c;
            setFormatter(new Formatter() {
                public String format(LogRecord r) {
                    return String.format("%tT.%tL [%-5s] %s%n", r.getMillis(), r.getMillis(), r.getLevel(), formatMessage(r));
                }
            });
            openToday();
        }
        private void openToday() {
            String d = today(); if (d.equals(currentDate)) return; currentDate = d;
            try {
                Path f = LOGS.resolve(category).resolve(d + ".log");
                Files.createDirectories(f.getParent());
                setOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(f, StandardOpenOption.CREATE, StandardOpenOption.APPEND)));
            } catch (IOException e) { System.err.println("Log file error: "+e.getMessage()); }
        }
        public synchronized void publish(LogRecord r) {
            if (!today().equals(currentDate)) openToday();
            super.publish(r); flush();
        }
    }

    /** PrintStream that writes to original stream AND daily log file directly */
    static class TeeStream extends PrintStream {
        private final PrintStream orig;
        private final StringBuilder lineBuf = new StringBuilder();

        TeeStream(PrintStream orig) {
            super(new OutputStream() { public void write(int b) { orig.write(b); } }, true);
            this.orig = orig;
        }

        @Override
        public void write(byte[] buf, int off, int len) {
            // Write to original console
            orig.write(buf, off, len);
            // Buffer and write lines to log file
            for (int i = off; i < off + len; i++) {
                char c = (char) buf[i];
                if (c == '\n' || c == '\r') {
                    if (lineBuf.length() > 0) {
                        appendToLog(lineBuf.toString());
                        lineBuf.setLength(0);
                    }
                } else {
                    lineBuf.append(c);
                }
            }
        }

        @Override
        public void write(int b) {
            orig.write(b);
            char c = (char) b;
            if (c == '\n' || c == '\r') {
                if (lineBuf.length() > 0) {
                    appendToLog(lineBuf.toString());
                    lineBuf.setLength(0);
                }
            } else {
                lineBuf.append(c);
            }
        }

        private void appendToLog(String line) {
            try {
                Path f = LOGS.resolve("kernel").resolve(today() + ".log");
                Files.createDirectories(f.getParent());
                String ts = java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
                Files.writeString(f, ts + " " + line + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {}
        }

        @Override public void flush() { orig.flush(); super.flush(); }
        @Override public void close() { orig.close(); super.close(); }
    }
}
