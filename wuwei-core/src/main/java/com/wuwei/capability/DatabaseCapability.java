package com.wuwei.capability;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Per-skill relational SQLite database capability with guardrails.
 *
 * Each skill gets its own isolated SQLite file at:
 *   ~/.wuwei/skills/<skillId>/phenotype/data.db
 *
 * JS API:
 *   capability.db.run(sql)             — DDL (CREATE TABLE/INDEX/VIEW, ALTER, DROP)
 *   capability.db.query(sql, [params]) — SELECT → [{col: val, ...}]
 *   capability.db.execute(sql, [params]) — INSERT/UPDATE/DELETE → {changes: N}
 */
public class DatabaseCapability {

    private static final Logger log = LoggerFactory.getLogger(DatabaseCapability.class);

    // DDL whitelist
    private static final Pattern DDL_PATTERN = Pattern.compile(
        "^(CREATE\\s+(TABLE|INDEX|VIEW)|ALTER\\s+TABLE|DROP\\s+(TABLE|INDEX))\\s",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    // DML whitelist
    private static final Pattern DML_PATTERN = Pattern.compile(
        "^(SELECT|INSERT|UPDATE|DELETE|REPLACE)\\s",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    // Forbidden SQL keywords (even inside DDL/DML)
    private static final Set<String> FORBIDDEN_KEYWORDS = Set.of(
        "PRAGMA", "VACUUM", "ATTACH", "DETACH", "REINDEX", "SAVEPOINT", "RELEASE"
    );

    private static final int QUERY_TIMEOUT_SEC = 5;
    private static final int MAX_ROWS = 10_000;
    private static final long MAX_DB_SIZE_BYTES = 100L * 1024 * 1024;
    private static final int MAX_TABLES = 50;

    private final String skillsDir;
    private final ConcurrentHashMap<String, Connection> connections = new ConcurrentHashMap<>();

    public DatabaseCapability() {
        String home = System.getProperty("user.home");
        this.skillsDir = Paths.get(home, ".wuwei", "skills").toString();
    }

    // ── Connection management ──────────────────────────────────────

    private Connection getConn(String skillId) throws SQLException {
        return connections.computeIfAbsent(skillId, id -> {
            String dbPath = Paths.get(skillsDir, id, "phenotype", "data.db").toString();
            try {
                Files.createDirectories(Paths.get(dbPath).getParent());
                Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                try (Statement stmt = c.createStatement()) {
                    stmt.execute("PRAGMA journal_mode=WAL");
                    stmt.execute("PRAGMA synchronous=NORMAL");
                    stmt.execute("PRAGMA busy_timeout=3000");
                    stmt.execute("PRAGMA foreign_keys=ON");
                }
                log.info("Opened data db for skill={} path={}", id, dbPath);
                return c;
            } catch (Exception e) {
                throw new RuntimeException("Failed to open data db for " + id, e);
            }
        });
    }

    // ── Check DB size ──────────────────────────────────────────────

    private void checkDbSize(String skillId) {
        Path dbPath = Paths.get(skillsDir, skillId, "phenotype", "data.db");
        try {
            if (Files.exists(dbPath) && Files.size(dbPath) > MAX_DB_SIZE_BYTES) {
                throw new RuntimeException("DB_SIZE_LIMIT:Database file exceeded 100MB limit for " + skillId);
            }
        } catch (RuntimeException e) { throw e; }
        catch (Exception ignored) {}
    }

    // ── Check table count ──────────────────────────────────────────

    private void checkTableCount(String skillId) {
        try (Statement stmt = getConn(skillId).createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT COUNT(*) FROM sqlite_master WHERE type='table'")) {
            if (rs.next() && rs.getInt(1) > MAX_TABLES) {
                throw new RuntimeException("DB_TABLE_LIMIT:Maximum table count (" + MAX_TABLES + ") exceeded");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check table count: " + e.getMessage(), e);
        }
    }

    // ── SQL validation ─────────────────────────────────────────────

    private void validateDdl(String sql) {
        String upper = sql.toUpperCase().trim();
        if (!DDL_PATTERN.matcher(sql.trim()).find()) {
            throw new RuntimeException("DB_SQL_REJECTED:Only CREATE TABLE/INDEX/VIEW, ALTER TABLE, DROP TABLE/INDEX allowed");
        }
        for (String kw : FORBIDDEN_KEYWORDS) {
            if (upper.contains(kw)) {
                throw new RuntimeException("DB_SQL_REJECTED:Forbidden keyword: " + kw);
            }
        }
        if (hasMultipleStatements(sql)) {
            throw new RuntimeException("DB_SQL_REJECTED:Multiple statements not allowed");
        }
    }

    private void validateDml(String sql) {
        String upper = sql.toUpperCase().trim();
        if (!DML_PATTERN.matcher(sql.trim()).find()) {
            throw new RuntimeException("DB_SQL_REJECTED:Only SELECT/INSERT/UPDATE/DELETE/REPLACE allowed");
        }
        for (String kw : FORBIDDEN_KEYWORDS) {
            if (upper.contains(kw)) {
                throw new RuntimeException("DB_SQL_REJECTED:Forbidden keyword: " + kw);
            }
        }
        if (hasMultipleStatements(sql)) {
            throw new RuntimeException("DB_SQL_REJECTED:Multiple statements not allowed");
        }
    }

    private boolean hasMultipleStatements(String sql) {
        // Strip comments
        String stripped = sql.replaceAll("--[^\\n]*", "").replaceAll("/\\*.*?\\*/", "");
        int semicolons = 0;
        boolean inString = false;
        char stringChar = 0;
        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (inString) {
                if (c == stringChar) inString = false;
            } else {
                if (c == '\'' || c == '"') {
                    inString = true;
                    stringChar = c;
                } else if (c == ';' && i > 0 && i < stripped.length() - 1) {
                    // Check there's non-whitespace after this semicolon
                    String after = stripped.substring(i + 1).trim();
                    if (!after.isEmpty()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ── Public API (called from CapabilitySet ProxyObject) ─────────

    public ProxyObject forSkill(String skillId) {
        return new ProxyObject() {
            @Override
            public Object getMember(String key) {
                return switch (key) {
                    case "run" -> (ProxyExecutable) args ->
                        runSql(skillId, args[0].asString());
                    case "query" -> (ProxyExecutable) args ->
                        query(skillId, args[0].asString(), unwrapParams(args));
                    case "execute" -> (ProxyExecutable) args ->
                        execute(skillId, args[0].asString(), unwrapParams(args));
                    default -> null;
                };
            }

            @Override
            public boolean hasMember(String key) {
                return Set.of("run", "query", "execute").contains(key);
            }

            @Override
            public Set<String> getMemberKeys() { return Set.of("run", "query", "execute"); }

            @Override
            public void putMember(String key, Value value) {}

            @Override
            public boolean removeMember(String key) { return false; }
        };
    }

    // Extract params from args (args[1] through args[n])
    // Supports both flat params: query(sql, 1, "foo")
    // and array params: query(sql, [1, "foo"])
    private List<Object> unwrapParams(Value[] args) {
        List<Object> params = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            Value v = args[i];
            if (v.hasArrayElements()) {
                for (long j = 0; j < v.getArraySize(); j++) {
                    params.add(unwrapScalar(v.getArrayElement(j)));
                }
            } else {
                params.add(unwrapScalar(v));
            }
        }
        return params;
    }

    private Object unwrapScalar(Value v) {
        if (v.isNumber()) {
            double d = v.asDouble();
            if (d == Math.floor(d) && !Double.isInfinite(d)) return (long) d;
            return d;
        } else if (v.isString()) {
            return v.asString();
        } else if (v.isNull()) {
            return null;
        } else {
            return v.asString();
        }
    }

    // ── DDL: run(sql) ──────────────────────────────────────────────

    private Object runSql(String skillId, String sql) {
        log.debug("db.run [{}]: {}...", skillId,
            sql.length() > 60 ? sql.substring(0, 60) : sql);
        validateDdl(sql);
        checkDbSize(skillId);
        try {
            Connection conn = getConn(skillId);
            try (Statement stmt = conn.createStatement()) {
                stmt.setQueryTimeout(QUERY_TIMEOUT_SEC);
                stmt.execute(sql);
            }
            checkTableCount(skillId);
            return null;
        } catch (SQLException e) {
            log.warn("db.run failed [{}]: {}", skillId, e.getMessage());
            throw new RuntimeException("db.run failed: " + e.getMessage(), e);
        }
    }

    // ── SELECT: query(sql, params) ─────────────────────────────────

    private Object query(String skillId, String sql, List<Object> params) {
        log.debug("db.query [{}]: {}...", skillId,
            sql.length() > 60 ? sql.substring(0, 60) : sql);
        validateDml(sql);
        checkDbSize(skillId);
        try {
            Connection conn = getConn(skillId);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setQueryTimeout(QUERY_TIMEOUT_SEC);
                for (int i = 0; i < params.size(); i++) {
                    Object p = params.get(i);
                    if (p == null) ps.setNull(i + 1, Types.NULL);
                    else if (p instanceof Long l) ps.setLong(i + 1, l);
                    else if (p instanceof Double d) ps.setDouble(i + 1, d);
                    else ps.setString(i + 1, p.toString());
                }
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int cols = meta.getColumnCount();
                    List<Object> rows = new ArrayList<>();
                    int count = 0;
                    while (rs.next() && count < MAX_ROWS) {
                        final Map<String, Object> row = new LinkedHashMap<>();
                        for (int c = 1; c <= cols; c++) {
                            row.put(meta.getColumnName(c), rs.getObject(c));
                        }
                        // Wrap each row as ProxyObject so JS can access .title, .content etc.
                        rows.add(new ProxyObject() {
                            @Override public Object getMember(String key) { return row.get(key); }
                            @Override public boolean hasMember(String key) { return row.containsKey(key); }
                            @Override public Set<String> getMemberKeys() { return row.keySet(); }
                            @Override public void putMember(String key, Value value) {}
                            @Override public boolean removeMember(String key) { return false; }
                        });
                        count++;
                    }
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    List<Object> proxyRows = (List) rows;
                    return ProxyArray.fromList(proxyRows);
                }
            }
        } catch (SQLException e) {
            log.warn("db.query failed [{}]: {}", skillId, e.getMessage());
            throw new RuntimeException("db.query failed: " + e.getMessage(), e);
        }
    }

    // ── DML: execute(sql, params) → {changes: N} ───────────────────

    private Object execute(String skillId, String sql, List<Object> params) {
        log.debug("db.execute [{}]: {}...", skillId,
            sql.length() > 60 ? sql.substring(0, 60) : sql);
        validateDml(sql);
        checkDbSize(skillId);
        try {
            Connection conn = getConn(skillId);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setQueryTimeout(QUERY_TIMEOUT_SEC);
                for (int i = 0; i < params.size(); i++) {
                    Object p = params.get(i);
                    if (p == null) ps.setNull(i + 1, Types.NULL);
                    else if (p instanceof Long l) ps.setLong(i + 1, l);
                    else if (p instanceof Double d) ps.setDouble(i + 1, d);
                    else ps.setString(i + 1, p.toString());
                }
                int changes = ps.executeUpdate();
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("changes", changes);
                return CryptoCapability.mapProxy(result);
            }
        } catch (SQLException e) {
            log.warn("db.execute failed [{}]: {}", skillId, e.getMessage());
            throw new RuntimeException("db.execute failed: " + e.getMessage(), e);
        }
    }

    // ── Proxy execution for browser-js ─────────────────────────────

    public Object executeProxy(String skillId, String method, List<Object> args) {
        return switch (method) {
            case "run" -> runSql(skillId, (String) args.get(0));
            case "query" -> query(skillId, (String) args.get(0),
                args.size() > 1 ? args.subList(1, args.size()) : List.of());
            case "execute" -> {
                Object result = execute(skillId, (String) args.get(0),
                    args.size() > 1 ? args.subList(1, args.size()) : List.of());
                // Unwrap ProxyObject for JSON serialization
                if (result instanceof ProxyObject po) {
                    Map<String, Object> plain = new LinkedHashMap<>();
                    plain.put("changes", po.getMember("changes"));
                    yield plain;
                }
                yield result;
            }
            default -> Map.of("error", "Unknown db method: " + method);
        };
    }

    // ── Cleanup ─────────────────────────────────────────────────────

    public void close(String skillId) {
        Connection c = connections.remove(skillId);
        if (c != null) {
            try { c.close(); } catch (SQLException ignored) {}
            log.info("Closed data db for skill={}", skillId);
        }
    }

    public void deleteDb(String skillId) {
        close(skillId);
        Path dbPath = Paths.get(skillsDir, skillId, "phenotype", "data.db");
        try {
            Files.deleteIfExists(dbPath);
        } catch (Exception e) {
            log.warn("Failed to delete data.db for skill={}: {}", skillId, e.getMessage());
        }
    }
}
