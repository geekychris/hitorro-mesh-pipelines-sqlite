/*
 * Copyright (c) 2006-2026 Chris Collins
 */
package com.hitorro.mesh.pipelines.adapters.sqlite;

// Run with one mode: kvstore | lucene | sqlite | jvstype
import tools.jackson.databind.*; import tools.jackson.databind.node.*;
import com.hitorro.mesh.pipelines.parse.JobSpecYaml; import com.hitorro.mesh.pipelines.runtime.*; import com.hitorro.mesh.pipelines.sinks.SinkRegistry;
import java.io.*; import java.nio.charset.StandardCharsets; import java.nio.file.*; import java.sql.*; import java.util.*;
/**
 * Pins what this adapter writes and reads, through real local pipeline jobs, to what 3.1.0 did on
 * Jackson 2: the sqlite source over every column type (integer, real, big integer, numeric, blob, boolean, datetime, null, JSON text), typed bound parameters, aggregates and SQL/file errors. {@code jackson2-x-sqlite.txt} is the output of the same jobs run on the 3.1.0 sources
 * (generator: ~/hitorro-j3/tools/Jackson2PipelineAdaptersGolden.java.txt, mode {@code sqlite}, in the
 * migration workspace).
 */
class Jackson2FormatGoldenTest {
  static final Map<String, String> out = new LinkedHashMap<>();
  static void p(String k, Object v){ out.put(k, String.valueOf(v).replace("\\","\\\\").replace("\n","\\n").replace("\r","\\r")); }
  static boolean jackson(Throwable t){ return t.getClass().getName().startsWith("com.fasterxml.jackson") || t.getClass().getName().startsWith("tools.jackson"); }
  static String desc(Throwable t, boolean top){ boolean ioe = t instanceof IOException;
    if (t.getClass() == IOException.class && t.getCause() != null && jackson(t.getCause())) t = t.getCause();
    String s = (top ? (ioe ? "IOE:" : "RTE:") : "") + (jackson(t) ? "jackson" : t.getClass().getSimpleName());
    if (!jackson(t) && t.getCause() != null) s += "<" + desc(t.getCause(), false) + ">"; return s; }
  static String tryIt(java.util.concurrent.Callable<Object> c){ try { return String.valueOf(c.call()); } catch(Throwable t){ return "!"+desc(t, true); } }
  static String types(JsonNode n){ StringBuilder t=new StringBuilder(); if (n == null) return "null"; if (n.isArray()) { t.append('['); for (JsonNode e : n) t.append(types(e)).append(','); return t.append(']').toString(); }
    if (!n.isObject()) return n.getClass().getSimpleName(); t.append('{'); Iterator<String> it=n.propertyNames().iterator(); while(it.hasNext()){String k=it.next(); t.append(k+":"+types(n.get(k))+",");} return t.append('}').toString(); }
  static final String ROWS = "[{id: a1, name: \"Alpha Doc\", pop: 1500000000, score: 1.10, ok: yes, tags: [x, y], meta: {k: 0x1F}, n: ~, big: 12345678901234567890, when: 2026-01-02},"
    + " {id: 7, name: \"B\u00e9ta/ two\", pop: \"12\", score: -0.0, ok: off, dec: 123456789012345678901234567890.5},"
    + " {id: {o: 1}, name: \"object id\"}, {name: \"no id\", v: [1, 2.5, \"s\", null]}, {id: a1, name: \"Alpha replaced\", v: 2}]";
  static String run(Path home, String yaml) throws Exception {
    SinkRegistry reg = new SinkRegistry(home);
    try (JobRunner r = new JobRunner(reg)) {
      JobStatus st = r.run(JobSpecYaml.parse(yaml.replace("HOME", home.toString())));
      StringBuilder sb = new StringBuilder(st.state + " err=" + (st.error == null ? null : st.error.replace(home.toString(), "HOME").replaceAll("@[0-9a-f]{4,}", "@<hash>")));
      for (Map.Entry<String, JobStatus.NodeStatus> e : st.nodes().entrySet()) sb.append(" ").append(e.getKey()).append(":").append(e.getValue().state).append(":").append(e.getValue().rowsIn).append("/").append(e.getValue().rowsOut);
      List<String> rows = new ArrayList<>(); for (JsonNode n : reg.memoryTable("mem")) rows.add(n + " " + types(n)); Collections.sort(rows);
      return sb.append(" mem=").append(rows).toString();
    }
  }
  static String job(String source, String steps, String sinks) { return "job: g\nnodes:\n  - id: n1\n    pipeline:\n      source: " + source + "\n" + (steps == null ? "" : "      steps: " + steps + "\n") + "      sinks: " + sinks + "\n"; }
  static final String MEM = "[{kind: memory-table, name: mem}]";
  public static Map<String, String> compute(Path dir, String mode) throws Exception {
    out.clear();
    ObjectMapper om = com.hitorro.util.json.JsonMappers.json();
    String inline = "{kind: inline, rows: " + ROWS + "}";
    switch (mode) {
      case "kvstore": {
        Path h = dir.resolve("kv"); Files.createDirectories(h);
        p("kv.write.id", tryIt(() -> run(h, job(inline, null, "[{kind: kvstore, name: docs, keyExpr: id}]"))));
        p("kv.write.nested", tryIt(() -> run(h, job(inline, null, "[{kind: kvstore, name: docs2, keyExpr: meta.k}]"))));
        p("kv.write.default", tryIt(() -> run(h, job(inline, null, "[{kind: kvstore, name: docs3}]"))));
        for (String n : new String[]{"docs", "docs2", "docs3", "missing"}) p("kv.read." + n, tryIt(() -> run(h, job("{kind: kvstore, name: " + n + "}", null, MEM))));
        p("kv.read.thenSteps", tryIt(() -> run(h, job("{kind: kvstore, name: docs}", "[{kind: project, cols: [id, ok, meta]}]", MEM))));
        break; }
      case "lucene": {
        Path h = dir.resolve("lu"); Files.createDirectories(h);
        p("lucene.write.src", tryIt(() -> run(h, job(inline, null, "[{kind: lucene, name: docs, storeSource: true}]"))));
        p("lucene.write.nosrc", tryIt(() -> run(h, job(inline, null, "[{kind: lucene, name: docs2, storeSource: false}]"))));
        for (String n : new String[]{"docs", "docs2"}) for (String q : new String[]{"*:*", "_text:alpha", "name:\\\"Alpha Doc\\\"", "id:7", "ok:true", "pop:12", "bad query (("})
          p("lucene.read." + n + "." + q, tryIt(() -> run(h, job("{kind: lucene, name: " + n + ", query: \"" + q + "\"}", null, MEM))));
        p("lucene.read.missing", tryIt(() -> run(h, job("{kind: lucene, name: nope, query: \"*:*\"}", null, MEM))));
        break; }
      case "sqlite": {
        Path db = dir.resolve("t.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) { try (Statement s = c.createStatement()) {
          s.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT, score REAL, big INTEGER, dec NUMERIC, blob BLOB, flag BOOLEAN, ts DATETIME, nul TEXT, js TEXT)");
          s.execute("INSERT INTO t VALUES (1, 'Alpha \u00e9/', 1.10, 12345678901, 123.456, x'00FF10', 1, '2026-01-02 03:04:05', NULL, '{\"a\":[1,2]}')");
          s.execute("INSERT INTO t VALUES (2, '', -0.0, 9223372036854775807, '12', NULL, 0, 1700000000123, NULL, 'not json')");
          s.execute("INSERT INTO t VALUES (3, NULL, 1e30, -1, 1e400, x'', 'yes', NULL, 'x', NULL)"); } }
        String[][] qs = {{"all", "SELECT * FROM t ORDER BY id", "[]"}, {"params", "SELECT id, name FROM t WHERE id >= ? AND name IS NOT ? AND ? = 1 ORDER BY id", "[2, \"zzz\", true]"},
          {"paramTypes", "SELECT ? AS i, ? AS l, ? AS d, ? AS b, ? AS s, ? AS n", "[1, 12345678901, 1.5, false, \"x\", null]"}, {"expr", "SELECT count(*) AS c, sum(score) AS s, avg(big) AS a, group_concat(name) AS g FROM t", "[]"},
          {"badSql", "SELEC nope", "[]"}};
        for (String[] q : qs) p("sqlite." + q[0], tryIt(() -> run(dir, job("{kind: sqlite, path: \"" + db + "\", query: \"" + q[1] + "\", params: " + q[2] + "}", null, MEM))));
        p("sqlite.missingDb", tryIt(() -> run(dir, job("{kind: sqlite, path: \"" + dir.resolve("none.db") + "\", query: \"SELECT 1\"}", null, MEM))));
        break; }
      case "jvstype": {
        Path h = dir.resolve("jv"); Files.createDirectories(h);
        String[] scripts = {"copyAll()", "copyAll()\nset \"target.stamp\", \"processed\"", "set \"a.b.c\", 1.5\nset \"x\", true", "copyAll()\nthrow new RuntimeException(\"boom\")", "this is not groovy [["};
        for (int i = 0; i < scripts.length; i++) { final String sc = scripts[i];
          p("jvsGroovy." + i, tryIt(() -> run(h, job(inline, "[{kind: jvs-groovy, script: " + om.writeValueAsString(sc) + "}]", MEM)))); }
        p("jvsLucene.write", tryIt(() -> run(h, job(inline, null, "[{kind: jvs-lucene, name: jl, typeJsonResource: \"classpath:types/demo_enriched_article.json\", storeSource: true}]"))));
        p("jvsLucene.sidecar", tryIt(() -> { Path f = h.resolve("lucene/jl/.jvs-type.json"); return Files.exists(f) ? new String(Files.readAllBytes(f), StandardCharsets.UTF_8).length() + ":" + om.readTree(f.toFile()).get("name") : "-"; }));
        p("jvsEnrich", tryIt(() -> run(h, job(inline, "[{kind: jvs-enrich, tags: [basic], typeJsonResource: \"classpath:types/demo_enriched_article.json\"}]", MEM))));
        break; }
      default: throw new IllegalArgumentException(mode);
    }
    return out;
  }

  static Map<String, String> load(String name) throws IOException {
    Map<String, String> m = new LinkedHashMap<>();
    try (InputStream in = Jackson2FormatGoldenTest.class.getResourceAsStream(name)) {
      if (in == null) return m;
      for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
        if (line.isEmpty()) continue;
        int i = line.indexOf('=');
        m.put(line.substring(0, i), line.substring(i + 1));
      }
    }
    return m;
  }

  @org.junit.jupiter.api.Test
  void matchesJackson2Output(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
    Map<String, String> expected = load("jackson2-x-sqlite.txt");
    
    Map<String, String> actual = new LinkedHashMap<>();
    compute(dir, "sqlite").forEach((k, v) -> actual.put(k, v.replace("StringNode", "TextNode")));
    org.assertj.core.api.Assertions.assertThat(expected).hasSize(6);
    org.assertj.core.api.Assertions.assertThat(actual).containsExactlyEntriesOf(expected);
  }
}

