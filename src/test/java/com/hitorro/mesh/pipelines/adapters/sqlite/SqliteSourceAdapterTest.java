/*
 * Copyright (c) 2006-2026 Chris Collins
 */
package com.hitorro.mesh.pipelines.adapters.sqlite;

import com.fasterxml.jackson.databind.JsonNode;
import com.hitorro.mesh.pipelines.model.SourceSpec;
import com.hitorro.mesh.pipelines.sinks.SinkRegistry;
import com.hitorro.mesh.pipelines.sources.SourceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the SQLite source through the full pipelines
 * {@link SourceFactory} path — no direct adapter invocation, so we're
 * proving both the adapter logic AND that ServiceLoader picks it up.
 *
 * <p>Every test builds a small SQLite DB under {@code @TempDir},
 * runs a query, and asserts on the streamed rows.</p>
 */
class SqliteSourceAdapterTest {

    @TempDir Path tmp;
    Path dbFile;
    SourceFactory sf;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = tmp.resolve("test.db");
        sf     = new SourceFactory(new SinkRegistry(tmp));
    }

    // ---------------------------------------------------------- round-trip

    @Test
    void basicSelect_streamsAllRows() throws Exception {
        withDb(stmt -> {
            stmt.execute("CREATE TABLE people (id INTEGER PRIMARY KEY, name TEXT, age INTEGER)");
            stmt.execute("INSERT INTO people VALUES (1, 'alice', 30), (2, 'bob', 25), (3, 'carol', 47)");
        });

        List<JsonNode> rows = drain(sf.open(
                new SourceSpec.Sqlite(dbFile.toString(), "SELECT * FROM people ORDER BY id"),
                new AtomicBoolean()));

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).get("id").asLong()).isEqualTo(1);
        assertThat(rows.get(0).get("name").asText()).isEqualTo("alice");
        assertThat(rows.get(0).get("age").asLong()).isEqualTo(30);
        assertThat(rows.get(2).get("name").asText()).isEqualTo("carol");
    }

    @Test
    void emptyResult_noRows() throws Exception {
        withDb(stmt -> stmt.execute("CREATE TABLE t (a INTEGER)"));
        List<JsonNode> rows = drain(sf.open(
                new SourceSpec.Sqlite(dbFile.toString(), "SELECT * FROM t"),
                new AtomicBoolean()));
        assertThat(rows).isEmpty();
    }

    @Test
    void columnAliases_preservedInJsonKeys() throws Exception {
        // AS aliases must show up as the JSON keys — otherwise projected
        // aggregates come back as "COUNT(*)" instead of the friendly "n".
        withDb(stmt -> {
            stmt.execute("CREATE TABLE t (v INTEGER)");
            stmt.execute("INSERT INTO t VALUES (1), (2), (3)");
        });
        List<JsonNode> rows = drain(sf.open(
                new SourceSpec.Sqlite(dbFile.toString(),
                        "SELECT COUNT(*) AS n, SUM(v) AS total FROM t"),
                new AtomicBoolean()));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("n").asLong()).isEqualTo(3);
        assertThat(rows.get(0).get("total").asLong()).isEqualTo(6);
    }

    // ---------------------------------------------------------- type coercion

    @Test
    void coercesAllTypes_intRealTextBlobNull() throws Exception {
        withDb(stmt -> {
            stmt.execute("CREATE TABLE t (i INTEGER, r REAL, s TEXT, b BLOB, n INTEGER)");
            stmt.execute("INSERT INTO t VALUES (42, 3.14, 'hello', x'deadbeef', NULL)");
        });
        var rows = drain(sf.open(new SourceSpec.Sqlite(dbFile.toString(), "SELECT * FROM t"),
                new AtomicBoolean()));
        assertThat(rows).hasSize(1);
        JsonNode r = rows.get(0);
        assertThat(r.get("i").asLong()).isEqualTo(42);
        assertThat(r.get("r").asDouble()).isEqualTo(3.14);
        assertThat(r.get("s").asText()).isEqualTo("hello");
        // BLOB comes out base64-encoded — decoding back gives the bytes.
        byte[] bytes = Base64.getDecoder().decode(r.get("b").asText());
        assertThat(bytes).containsExactly((byte)0xde, (byte)0xad, (byte)0xbe, (byte)0xef);
        assertThat(r.get("n").isNull()).isTrue();
    }

    @Test
    void nullNumericFields_areJsonNull_notZero() throws Exception {
        // Regression protection: rs.getLong returns 0 for NULL — we must
        // check wasNull() to distinguish. Otherwise nullable counts would
        // silently become 0 in the pipeline.
        withDb(stmt -> {
            stmt.execute("CREATE TABLE t (v INTEGER)");
            stmt.execute("INSERT INTO t VALUES (NULL), (0), (NULL)");
        });
        var rows = drain(sf.open(new SourceSpec.Sqlite(dbFile.toString(), "SELECT v FROM t ORDER BY rowid"),
                new AtomicBoolean()));
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).get("v").isNull()).isTrue();
        assertThat(rows.get(1).get("v").asLong()).isEqualTo(0);
        assertThat(rows.get(2).get("v").isNull()).isTrue();
    }

    // ---------------------------------------------------------- params

    @Test
    void positionalParams_boundInOrder() throws Exception {
        withDb(stmt -> {
            stmt.execute("CREATE TABLE t (id INTEGER, name TEXT)");
            stmt.execute("INSERT INTO t VALUES (1, 'alice'), (2, 'bob'), (3, 'carol')");
        });
        var rows = drain(sf.open(new SourceSpec.Sqlite(dbFile.toString(),
                "SELECT name FROM t WHERE id BETWEEN ? AND ?",
                List.of(2, 3)), new AtomicBoolean()));
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(r -> r.get("name").asText())
                .containsExactly("bob", "carol");
    }

    @Test
    void nullParam_bindsAsNull() throws Exception {
        withDb(stmt -> stmt.execute("CREATE TABLE t (v INTEGER)"));
        List<Object> ps = new ArrayList<>(); ps.add(null);
        var rows = drain(sf.open(new SourceSpec.Sqlite(dbFile.toString(),
                "SELECT ? AS v", ps), new AtomicBoolean()));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("v").isNull()).isTrue();
    }

    // ---------------------------------------------------------- error paths

    @Test
    void missingFile_throwsFileNotFound() {
        assertThatThrownBy(() -> sf.open(
                new SourceSpec.Sqlite(tmp.resolve("nope.db").toString(), "SELECT 1"),
                new AtomicBoolean()))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("no such database");
    }

    @Test
    void badSql_throwsWithSourceMessage() throws Exception {
        withDb(stmt -> stmt.execute("CREATE TABLE t (a INTEGER)"));
        assertThatThrownBy(() -> sf.open(new SourceSpec.Sqlite(dbFile.toString(),
                "SELECT * FROM nonexistent"), new AtomicBoolean()))
                .hasMessageContaining("no such table")
                .hasMessageContaining("nonexistent");
    }

    // ---------------------------------------------------------- read-only

    @Test
    void readOnlyMode_preventsMutation() throws Exception {
        // Semantic proof: grab a raw connection via the adapter's own
        // openReadOnly() helper, try an INSERT via executeUpdate — must
        // fail with SQLite's "readonly database" error. This proves the
        // SQLiteConfig actually opens with SQLITE_OPEN_READONLY, not
        // just at the JDBC layer.
        withDb(stmt -> stmt.execute("CREATE TABLE t (a INTEGER)"));
        try (Connection roConn = SqliteSourceAdapter.openReadOnly(dbFile);
             Statement stmt = roConn.createStatement()) {
            assertThatThrownBy(() -> stmt.executeUpdate("INSERT INTO t VALUES (1)"))
                    .hasMessageContaining("readonly");
        }
    }

    // ---------------------------------------------------------- cancellation

    @Test
    void cancelFlag_stopsIterationMidStream() throws Exception {
        withDb(stmt -> {
            stmt.execute("CREATE TABLE t (v INTEGER)");
            // Insert 1000 rows; iterate a few, flip the cancel flag,
            // assert that hasNext returns false without exhausting the RS.
            stmt.execute("WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM c WHERE x<1000) "
                    + "INSERT INTO t SELECT x FROM c");
        });

        AtomicBoolean cancelled = new AtomicBoolean(false);
        Iterator<JsonNode> it = sf.open(new SourceSpec.Sqlite(dbFile.toString(),
                "SELECT * FROM t"), cancelled);

        int read = 0;
        while (it.hasNext()) {
            it.next();
            read++;
            if (read == 5) {
                cancelled.set(true);
                break;
            }
        }
        assertThat(read).isEqualTo(5);
        // A subsequent hasNext() while cancelled must return false + close resources.
        assertThat(it.hasNext()).isFalse();
    }

    // ---------------------------------------------------------- path expansion

    @Test
    void resolvePath_expandsHomeDirTilde() {
        Path expanded = SqliteSourceAdapter.resolvePath("~/foo/bar.db");
        assertThat(expanded.toString())
                .startsWith(System.getProperty("user.home"))
                .endsWith("foo/bar.db");
    }

    @Test
    void resolvePath_absolutePathUnchanged() {
        Path abs = SqliteSourceAdapter.resolvePath("/etc/hosts");
        assertThat(abs.toString()).isEqualTo("/etc/hosts");
    }

    @Test
    void resolvePath_nullThrows() {
        assertThatThrownBy(() -> SqliteSourceAdapter.resolvePath(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------- helpers

    @FunctionalInterface interface DbInit { void init(Statement s) throws Exception; }

    private void withDb(DbInit init) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             Statement s = c.createStatement()) {
            init.init(s);
        }
    }

    private static List<JsonNode> drain(Iterator<JsonNode> it) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        try { while (it.hasNext()) out.add(it.next()); }
        finally { if (it instanceof AutoCloseable c) c.close(); }
        return out;
    }
}
