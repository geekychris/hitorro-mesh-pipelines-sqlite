# hitorro-mesh-pipelines-sqlite

Read-only SQLite source for
[hitorro-mesh-pipelines](https://github.com/geekychris/hitorro-mesh-pipelines).
Drop this jar on the driver classpath and the pipelines'
`SourceFactory` auto-loads the adapter via `ServiceLoader` — any
`{kind: sqlite, path: "...", query: "SELECT ..."}` in a job spec
starts reading from a real SQLite DB with zero extra wiring.

Turns any local SQLite database — Mac Mail, Photos, Messages, Safari
history, application caches, your own DuckDB-compatible extracts —
into a first-class pipeline source, immediately composable with every
downstream step + sink the pipelines runtime provides.

## Wire shape

```yaml
source:
  kind: sqlite
  path: "~/Library/Mail/V10/MailData/Envelope Index"    # ~/ expands to $HOME
  query: |
    SELECT s.address AS sender, COUNT(*) AS n
    FROM messages m
    JOIN addresses s ON s.ROWID = m.sender
    WHERE m.date_received > ?
    GROUP BY sender ORDER BY n DESC LIMIT 100
  params:
    - 1735689600       # positional ? binding (Unix epoch: 2025-01-01)
```

Or via the Groovy DSL:

```groovy
source sqlite: "~/Library/Mail/V10/MailData/Envelope Index",
       query: "SELECT COUNT(*) AS n FROM messages"
```

## Row shape

Every row → JSON object keyed on column names (or AS aliases).
Type coercion:

| SQLite  | JSON            |
|---------|-----------------|
| INTEGER | number (long)   |
| REAL    | number (double) |
| TEXT    | string          |
| BLOB    | string (base64) |
| NULL    | JSON null       |

## Read-only guaranteed

The adapter opens the DB with `SQLiteConfig.setReadOnly(true)` —
SQLite's own `SQLITE_OPEN_READONLY`. Any attempt at DDL/DML fails with
`attempt to write a readonly database`. This is critical for Mac
Mail / Photos / etc. — the OS relies on those databases for real-time
state; a mutation would silently corrupt your inbox.

No sink counterpart shipped. "Write into SQLite" has no obvious
general semantics (UPSERT? by which key? conflict resolution?), and
the durable sinks that already exist (`KvStore`, `Lucene`,
`NdjsonFile`, `JsonFile`, `CsvFile`) cover the "materialize results"
case cleanly.

## macOS Full Disk Access (READ THIS)

Reading `~/Library/Mail/`, `~/Library/Messages/`, and other
system-managed SQLite files requires the *process running the JVM* to
have **Full Disk Access** in **System Settings → Privacy & Security →
Full Disk Access**. Add either:

- The Terminal / iTerm2 / IDE that launches the driver, OR
- The `java` binary directly (`/usr/bin/java` or your JDK's
  `bin/java`)

Without FDA, the adapter fails with `SQLITE_IOERR` / "Operation not
permitted" on the first query — you'll see the error in the driver
log immediately (not a subtle failure mode).

## Distribution constraint

SQLite sources execute **driver-local only**. `PipelineScheduler`
rejects them upfront on the `/mesh/jobs/run-distributed` path with a
clear error — the DB file lives on the driver host, not on remote
agents, and there's no way to "ship a query" to a Mac that doesn't
have your Mail installation.

Workaround pattern for hybrid jobs:

1. Run a driver-local job that scans the SQLite DB and materialises
   to a shared sink (kvstore / lucene / ndjson file on shared storage).
2. Run a second `/run-distributed` job that reads from that shared
   sink and does the heavy fan-out work across agents.

## Concurrent access

Uses `busyTimeout=5000ms` — brief overlaps with the owning app
(Mail.app is writing during a checkpoint, Photos.app is indexing) are
handled gracefully. SQLite WAL mode allows concurrent readers
alongside a writer, so our query gets a consistent snapshot at start
time.

## Bundled examples

- `examples/jobs/mail-top-domains.yaml` — sender → domain rollup with
  a Groovy-map step for the domain extraction
- `examples/jobs/mail-index.yaml` — index Mail into Lucene for
  full-text search over subject + sender

## Tests

14 tests in `SqliteSourceAdapterTest` covering:
- Basic SELECT + streaming iteration
- Column aliases preserved in JSON keys
- Full type coercion (INTEGER / REAL / TEXT / BLOB → base64 / NULL)
- Null-safe numeric extraction (rs.getLong returns 0 for NULL —
  we `wasNull()`-check)
- Positional param binding (including bound NULL)
- Missing file → clean IOException
- Bad SQL → clean SQLException with source message
- Semantic read-only proof: raw connection via `openReadOnly()`
  rejects INSERT with "readonly database"
- Cancel flag mid-stream stops iteration + closes resources
- Path helper: `~/` expansion, absolute unchanged, null rejected

## Dependencies

- `org.xerial:sqlite-jdbc` — self-contained JNI SQLite driver, no
  system libsqlite required.
