/*
 * Copyright (c) 2006-2026 Chris Collins
 */
package com.hitorro.mesh.pipelines.adapters.sqlite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hitorro.mesh.pipelines.model.SourceSpec;
import com.hitorro.mesh.pipelines.sources.SourceAdapter;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ServiceLoader-registered {@link SourceAdapter} for
 * {@link SourceSpec.Sqlite}. Opens the target DB read-only, executes
 * the query with optional positional-parameter binding, and streams
 * rows one-by-one as {@link JsonNode}s so a large query result doesn't
 * materialise into memory before the pipeline can consume it.
 *
 * <h3>Type coercion</h3>
 * SQLite column types → JSON:
 * <ul>
 *   <li>{@code INTEGER} → JSON number (long)</li>
 *   <li>{@code REAL} → JSON number (double)</li>
 *   <li>{@code TEXT} → JSON string</li>
 *   <li>{@code BLOB} → JSON string, base64-encoded</li>
 *   <li>{@code NULL} → JSON null (field present, value null)</li>
 *   <li>Anything else → {@code getString()} fallback</li>
 * </ul>
 *
 * <h3>Read-only</h3>
 * JDBC URL always includes {@code ?mode=ro} so nothing the pipeline
 * runs can mutate the source file. Critical for Mac Mail / Photos /
 * Messages / etc. which the OS relies on for real-time state.
 *
 * <h3>Cancellation</h3>
 * The {@code cancelled} flag is checked between rows — a DELETE on
 * {@code /mesh/jobs/{id}} stops iteration cleanly (closes the result
 * set, statement, and connection in reverse order).
 */
public final class SqliteSourceAdapter implements SourceAdapter {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public boolean handles(SourceSpec spec) {
        return spec instanceof SourceSpec.Sqlite;
    }

    @Override
    public Iterator<JsonNode> open(SourceSpec spec, Path home, AtomicBoolean cancelled) throws Exception {
        SourceSpec.Sqlite s = (SourceSpec.Sqlite) spec;
        Path dbPath = resolvePath(s.path());
        if (!java.nio.file.Files.isRegularFile(dbPath)) {
            throw new java.io.FileNotFoundException(
                    "sqlite source: no such database file: " + dbPath);
        }
        Connection conn = openReadOnly(dbPath);
        try {
            PreparedStatement ps = conn.prepareStatement(s.query());
            bindParams(ps, s.params());
            ResultSet rs = ps.executeQuery();
            return new StreamingIterator(conn, ps, rs, cancelled);
        } catch (SQLException e) {
            try { conn.close(); } catch (Exception ignore) { }
            throw new SQLException("sqlite query failed: " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------- helpers

    /**
     * Open a read-only SQLite connection with a busy timeout. Package-
     * private so the read-only invariant is directly testable — the
     * SQLiteConfig approach is used because xerial-sqlite ignores
     * URL-embedded {@code ?mode=ro}; only the config object's
     * {@code setReadOnly(true)} opens the DB with {@code SQLITE_OPEN_READONLY},
     * which makes any DDL/DML fail with SQLite's own "readonly database"
     * error.
     *
     * <p>{@code busyTimeout=5000ms} handles the brief window during a
     * WAL checkpoint when the owning app (Mail.app, Photos.app, …) has
     * the DB briefly locked. Without it, concurrent access sometimes
     * throws SQLITE_BUSY.</p>
     */
    static Connection openReadOnly(Path dbPath) throws SQLException {
        org.sqlite.SQLiteConfig cfg = new org.sqlite.SQLiteConfig();
        cfg.setReadOnly(true);
        cfg.setBusyTimeout(5000);
        return DriverManager.getConnection(
                "jdbc:sqlite:" + dbPath.toAbsolutePath(),
                cfg.toProperties());
    }

    /** Expand {@code ~/…} to the user's home dir. Everything else is
     *  passed to {@link Paths#get} verbatim (absolute or relative to
     *  the driver's working dir). */
    static Path resolvePath(String s) {
        if (s == null) throw new IllegalArgumentException("sqlite source: path is required");
        String expanded = s.startsWith("~/")
                ? System.getProperty("user.home") + s.substring(1)
                : s;
        return Paths.get(expanded);
    }

    /** Bind positional {@code ?} parameters in list order. */
    private static void bindParams(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object v = params.get(i);
            if (v == null)                     ps.setNull(i + 1, Types.NULL);
            else if (v instanceof Integer x)   ps.setInt(i + 1, x);
            else if (v instanceof Long x)      ps.setLong(i + 1, x);
            else if (v instanceof Double x)    ps.setDouble(i + 1, x);
            else if (v instanceof Boolean x)   ps.setBoolean(i + 1, x);
            else if (v instanceof byte[] x)    ps.setBytes(i + 1, x);
            else                               ps.setString(i + 1, String.valueOf(v));
        }
    }

    /**
     * ResultSet-backed iterator that closes the driver resources when
     * exhausted, cancelled, or {@link AutoCloseable#close()} is called.
     * Buffers exactly ONE row ahead so {@link #hasNext} can advance the
     * cursor without losing a row.
     */
    static final class StreamingIterator implements Iterator<JsonNode>, AutoCloseable {
        private final Connection conn;
        private final PreparedStatement ps;
        private final ResultSet rs;
        private final AtomicBoolean cancelled;
        private final String[] colNames;
        private final int[]    colTypes;
        private JsonNode buffered;
        private boolean exhausted;

        StreamingIterator(Connection conn, PreparedStatement ps, ResultSet rs,
                          AtomicBoolean cancelled) throws SQLException {
            this.conn = conn;
            this.ps = ps;
            this.rs = rs;
            this.cancelled = cancelled == null ? new AtomicBoolean() : cancelled;
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            this.colNames = new String[n];
            this.colTypes = new int[n];
            for (int i = 0; i < n; i++) {
                // getColumnLabel honours AS aliases; falls back to name.
                colNames[i] = md.getColumnLabel(i + 1);
                colTypes[i] = md.getColumnType(i + 1);
            }
        }

        @Override
        public boolean hasNext() {
            if (buffered != null) return true;
            if (exhausted || cancelled.get()) return false;
            try {
                if (!rs.next()) { close(); return false; }
                buffered = extractRow();
                return true;
            } catch (Exception e) {
                closeQuietly();
                throw new RuntimeException("sqlite row read: " + e.getMessage(), e);
            }
        }

        @Override
        public JsonNode next() {
            if (!hasNext()) throw new NoSuchElementException();
            JsonNode out = buffered;
            buffered = null;
            return out;
        }

        private JsonNode extractRow() throws SQLException {
            ObjectNode row = JSON.createObjectNode();
            for (int i = 0; i < colNames.length; i++) {
                int idx = i + 1;
                switch (colTypes[i]) {
                    case Types.INTEGER, Types.BIGINT, Types.SMALLINT, Types.TINYINT -> {
                        long v = rs.getLong(idx);
                        if (rs.wasNull()) row.putNull(colNames[i]);
                        else              row.put(colNames[i], v);
                    }
                    case Types.REAL, Types.FLOAT, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL -> {
                        double v = rs.getDouble(idx);
                        if (rs.wasNull()) row.putNull(colNames[i]);
                        else              row.put(colNames[i], v);
                    }
                    case Types.BOOLEAN, Types.BIT -> {
                        boolean v = rs.getBoolean(idx);
                        if (rs.wasNull()) row.putNull(colNames[i]);
                        else              row.put(colNames[i], v);
                    }
                    case Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> {
                        byte[] v = rs.getBytes(idx);
                        if (v == null) row.putNull(colNames[i]);
                        else           row.put(colNames[i], Base64.getEncoder().encodeToString(v));
                    }
                    case Types.NULL -> row.putNull(colNames[i]);
                    default -> {
                        String v = rs.getString(idx);
                        if (v == null) row.putNull(colNames[i]);
                        else           row.put(colNames[i], v);
                    }
                }
            }
            return row;
        }

        @Override
        public void close() {
            exhausted = true;
            closeQuietly();
        }

        private void closeQuietly() {
            try { rs.close(); }   catch (Exception ignore) { }
            try { ps.close(); }   catch (Exception ignore) { }
            try { conn.close(); } catch (Exception ignore) { }
        }
    }
}
