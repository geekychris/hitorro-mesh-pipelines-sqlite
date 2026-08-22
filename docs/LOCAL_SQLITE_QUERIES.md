# Local SQLite queries cheatsheet

Query recipes for the five macOS SQLite databases the sqlite adapter
ships bundled register jobs for. Each database is registered as a
mesh table by running its corresponding `*-register` pipeline; queries
then run via `POST /mesh/queries` or the **Playground** tab.

| Database         | Register job              | Mesh table          | Rough size on my Mac |
|------------------|---------------------------|---------------------|----------------------|
| Mac Mail         | `mail-register`           | `mail_messages`     | 344k messages        |
| iMessage / SMS   | `messages-register`       | `messages_texts`    | 50k+ typical         |
| Safari history   | `safari-register`         | `safari_visits`     | 10k+ typical         |
| Apple Photos     | `photos-register`         | `photos_assets`     | 20k+ typical         |
| macOS Screen Time| `screentime-register`     | `screentime_events` | 100k+ typical        |

**Before any of these will work:** grant **Full Disk Access** to
whatever launches the driver JVM (Terminal / IDE / `java` binary) in
**System Settings → Privacy & Security → Full Disk Access**. Without
it every query returns "Operation not permitted" / `SQLITE_IOERR` on
the underlying SQLite open.

## Running a register job

Via the Jobs UI: **Pipelines** tab → paste the YAML → ▶ Run.

Via curl:

```bash
curl -sX POST http://localhost:8085/mesh/jobs/run \
  -H "content-type: application/x-yaml" \
  --data-binary @path/to/messages-register.yaml
```

Once it finishes (a few seconds to a minute depending on DB size), the
table appears on the **Cluster** tab's Inventory Matrix — click
▶ Playground on the row to jump straight into a query editor with
schema-derived snippets pre-loaded.

---

# Mac Mail (`mail_messages`)

After running the `mail-register` bundled job, the mesh SQL layer has
a `mail_messages` table with these columns:

| column                    | type    | notes                                        |
|---------------------------|---------|----------------------------------------------|
| `id`                      | long    | Envelope Index rowid — stable per message    |
| `received_ts`             | long    | Unix epoch seconds                           |
| `sent_ts`                 | long    | Unix epoch seconds (may be null)             |
| `received_iso`            | string  | ISO-8601 UTC (`2026-08-21T14:33:12Z`)        |
| `year_month`              | string  | `2026-08` — group-by-month friendly          |
| `day_of_week`             | string  | `Mon`, `Tue`, …                              |
| `hour_of_day`             | number  | 0–23 UTC                                     |
| `sender_address`          | string  | `alice@example.com`                          |
| `sender_name`             | string  | display-name comment from RFC 822 address    |
| `sender_domain`           | string  | raw domain — `242850154.mailchimpapp.com`    |
| `sender_domain_folded`    | string  | list-shaped subdomains folded to parent      |
| `subject_prefix`          | string  | `Re: `, `Fwd: `, … (may be null)             |
| `subject`                 | string  | subject sans prefix                          |
| `subject_full`            | string  | `subject_prefix + subject`                   |
| `summary`                 | string  | body preview from Envelope Index             |
| `mailbox_url`             | string  | `imap://alice@...`, `local://`, …            |
| `read`                    | number  | 1 = read, 0 = unread                         |
| `flagged`                 | number  | 1 = flagged                                  |
| `size`                    | number  | message size in bytes                        |
| `is_newsletter`           | boolean | heuristic — folded domain or `[list]` prefix |

Query via `POST /mesh/queries`:

```bash
curl -sX POST http://localhost:8085/mesh/queries \
  -H "content-type: application/json" \
  -d '{"sql": "SELECT sender_domain_folded AS domain, COUNT(*) AS n FROM mail_messages GROUP BY domain ORDER BY n DESC LIMIT 15"}'
```

Or via the UI: **Queries** tab → paste SQL → run.

## The classics

### Top 25 senders by domain (mailchimp-folded)

```sql
SELECT sender_domain_folded AS domain,
       COUNT(*) AS n
FROM mail_messages
GROUP BY sender_domain_folded
ORDER BY n DESC
LIMIT 25
```

### Top 25 individual senders

```sql
SELECT sender_address, sender_name, COUNT(*) AS n
FROM mail_messages
WHERE sender_address IS NOT NULL
GROUP BY sender_address
ORDER BY n DESC
LIMIT 25
```

### Newsletter volume (folded vs raw)

Shows the noise-reduction from the folded-domain heuristic — the
mailchimp-per-campaign inflation goes away.

```sql
SELECT
    COUNT(DISTINCT sender_domain)        AS raw_domain_count,
    COUNT(DISTINCT sender_domain_folded) AS folded_domain_count,
    SUM(is_newsletter)                   AS newsletter_message_count,
    COUNT(*)                             AS total_messages
FROM mail_messages
```

## Time-shape

### Volume by month (last 24 months)

```sql
SELECT year_month, COUNT(*) AS n
FROM mail_messages
GROUP BY year_month
ORDER BY year_month DESC
LIMIT 24
```

### Busiest hour of day

```sql
SELECT hour_of_day, COUNT(*) AS n
FROM mail_messages
GROUP BY hour_of_day
ORDER BY hour_of_day
```

### Busiest day of week

```sql
SELECT day_of_week, COUNT(*) AS n
FROM mail_messages
GROUP BY day_of_week
```

### Peak hour per top sender

```sql
SELECT sender_domain_folded, hour_of_day, COUNT(*) AS n
FROM mail_messages
WHERE sender_domain_folded IN (
    SELECT sender_domain_folded FROM mail_messages
    GROUP BY sender_domain_folded ORDER BY COUNT(*) DESC LIMIT 10
)
GROUP BY sender_domain_folded, hour_of_day
ORDER BY sender_domain_folded, n DESC
```

## Subject patterns

### Reply chains you're deep in

Messages you replied to (`Re:` prefix) grouped by base subject —
threads with the most back-and-forth.

```sql
SELECT subject, COUNT(*) AS reply_count
FROM mail_messages
WHERE subject_prefix LIKE 'Re:%'
GROUP BY subject
ORDER BY reply_count DESC
LIMIT 20
```

### Subjects mentioning a keyword

```sql
SELECT received_iso, sender_address, subject_full
FROM mail_messages
WHERE subject LIKE '%kubernetes%'
   OR summary LIKE '%kubernetes%'
ORDER BY received_ts DESC
LIMIT 50
```

### Longest subjects (surprisingly indicative of noise)

```sql
SELECT LENGTH(subject) AS len, sender_domain_folded, subject
FROM mail_messages
WHERE subject IS NOT NULL
ORDER BY len DESC
LIMIT 15
```

## Read / unread hygiene

### Unread messages per sender

```sql
SELECT sender_domain_folded, COUNT(*) AS unread
FROM mail_messages
WHERE read = 0
GROUP BY sender_domain_folded
ORDER BY unread DESC
LIMIT 25
```

### Domains you never read (>50 messages, 0 opened)

Prime unsubscribe candidates.

```sql
SELECT sender_domain_folded,
       COUNT(*) AS total,
       SUM(read) AS read_count
FROM mail_messages
GROUP BY sender_domain_folded
HAVING total > 50 AND read_count = 0
ORDER BY total DESC
```

### Read-rate per domain

```sql
SELECT sender_domain_folded,
       COUNT(*)                        AS total,
       SUM(read)                       AS read_count,
       ROUND(100.0 * SUM(read) / COUNT(*), 1) AS read_pct
FROM mail_messages
GROUP BY sender_domain_folded
HAVING total > 30
ORDER BY read_pct DESC, total DESC
```

## Size + flag

### Biggest messages

```sql
SELECT sender_address, subject, ROUND(size / 1024.0 / 1024.0, 2) AS mb
FROM mail_messages
ORDER BY size DESC
LIMIT 25
```

### Everything you've flagged for follow-up

```sql
SELECT received_iso, sender_address, subject_full
FROM mail_messages
WHERE flagged = 1
ORDER BY received_ts DESC
```

## Mailbox distribution

### Where do most messages live?

```sql
SELECT mailbox_url, COUNT(*) AS n
FROM mail_messages
GROUP BY mailbox_url
ORDER BY n DESC
```

### Per-account inbox volume this year

```sql
SELECT mailbox_url, COUNT(*) AS n
FROM mail_messages
WHERE year_month >= '2026-01'
GROUP BY mailbox_url
ORDER BY n DESC
```

## Cross-cutting

### First contact date per sender

```sql
SELECT sender_address,
       MIN(received_iso) AS first_seen,
       MAX(received_iso) AS last_seen,
       COUNT(*)          AS total
FROM mail_messages
WHERE sender_address IS NOT NULL
GROUP BY sender_address
HAVING total > 100
ORDER BY first_seen ASC
LIMIT 20
```

### "Dormant" contacts — people you used to hear from

Historical top-N by volume who haven't emailed in the last 12 months.

```sql
SELECT sender_address, COUNT(*) AS total, MAX(received_iso) AS last_seen
FROM mail_messages
WHERE sender_address IS NOT NULL
GROUP BY sender_address
HAVING total > 50
   AND MAX(received_ts) < strftime('%s','now','-12 months')
ORDER BY total DESC
LIMIT 20
```

## After the enriched index runs

Once `mail-enriched-index.yaml` finishes, the `mail-enriched` Lucene
index is searchable via the pipelines Lucene search endpoint:

```bash
# Full-text — matches enriched (segmented + stemmed) title AND body.
curl -s 'http://localhost:8085/mesh/lucene/mail-enriched/search?q=kubernetes'

# Field-scoped queries — take advantage of the type-aware projection.
curl -s 'http://localhost:8085/mesh/lucene/mail-enriched/search?q=sender_domain:hyperion-entertainment.com'

# NER-facet: find every message where an ORG entity was extracted
# containing "Apple" (populated by jvs-enrich on subject + summary).
curl -s 'http://localhost:8085/mesh/lucene/mail-enriched/search?q=title.mls.segmented_ner:*NE_Organization*Apple*'

# Restrict by domain + keyword.
curl -s 'http://localhost:8085/mesh/lucene/mail-enriched/search?q=sender_domain:substack.com+AND+title.mls.text:paul'
```

---

# iMessage / SMS (`messages_texts`)

Columns:

| column           | type    | notes                                     |
|------------------|---------|-------------------------------------------|
| `id`             | long    | `message.ROWID`                           |
| `date_ns`        | long    | Cocoa-epoch nanoseconds (raw)             |
| `sent_iso`       | string  | ISO-8601 UTC                              |
| `year_month`     | string  | `2026-08`                                 |
| `day_of_week`    | string  | `Mon`, `Tue`, …                           |
| `hour_of_day`    | number  | 0–23 UTC                                  |
| `text`           | string  | message body (may be null for stickers)   |
| `from_me`        | number  | 1 = you sent it                           |
| `read`           | number  | 1 = read                                  |
| `service`        | string  | `iMessage` or `SMS`                       |
| `contact`        | string  | phone number or email of the other party  |
| `contact_country`| string  | country code from handle                  |
| `chat_id`        | string  | chat guid                                 |
| `chat_name`      | string  | group chat display name (may be null)     |
| `chat_style`     | number  | 43 = 1:1, 45 = group                      |
| `is_group`       | boolean | derived — `chat_style == 45`              |
| `text_length`    | number  | character count of the message text       |

### Top people you text with

```sql
SELECT contact, COUNT(*) AS n
FROM messages_texts
WHERE contact IS NOT NULL
GROUP BY contact
ORDER BY n DESC
LIMIT 20
```

### Sent vs received per contact

```sql
SELECT contact,
       SUM(from_me)     AS sent,
       COUNT(*) - SUM(from_me) AS received,
       COUNT(*)         AS total
FROM messages_texts
WHERE contact IS NOT NULL
GROUP BY contact
HAVING total > 50
ORDER BY total DESC
LIMIT 20
```

### iMessage vs SMS split

```sql
SELECT service, COUNT(*) AS n
FROM messages_texts
GROUP BY service
```

### Volume by month (last 24 months)

```sql
SELECT year_month, COUNT(*) AS n
FROM messages_texts
GROUP BY year_month
ORDER BY year_month DESC
LIMIT 24
```

### Longest messages you've received

```sql
SELECT sent_iso, contact, text_length, SUBSTR(text, 1, 200) AS preview
FROM messages_texts
WHERE from_me = 0 AND text IS NOT NULL
ORDER BY text_length DESC
LIMIT 10
```

### Group chat activity

```sql
SELECT chat_name, COUNT(*) AS msgs, COUNT(DISTINCT contact) AS participants
FROM messages_texts
WHERE is_group = TRUE AND chat_name IS NOT NULL
GROUP BY chat_name
ORDER BY msgs DESC
LIMIT 15
```

### Peak texting hour (per top-5 contact)

```sql
SELECT contact, hour_of_day, COUNT(*) AS n
FROM messages_texts
WHERE contact IN (
    SELECT contact FROM messages_texts
    WHERE contact IS NOT NULL
    GROUP BY contact ORDER BY COUNT(*) DESC LIMIT 5
)
GROUP BY contact, hour_of_day
ORDER BY contact, n DESC
```

### Response-latency estimate per contact

Median time-to-reply is complex in pure SQL. As a proxy: for each
inbound message from a contact, find the next outbound message to
same contact within 24 hours; average that gap.

```sql
-- Approximate: average interval between consecutive messages with
-- the same contact where direction changes.
WITH pairs AS (
    SELECT contact,
           date_ns,
           from_me,
           LAG(date_ns)  OVER (PARTITION BY contact ORDER BY date_ns) AS prev_ns,
           LAG(from_me)  OVER (PARTITION BY contact ORDER BY date_ns) AS prev_from
    FROM messages_texts
    WHERE contact IS NOT NULL
)
SELECT contact,
       ROUND(AVG((date_ns - prev_ns) / 1e9) / 60.0, 1) AS avg_reply_min
FROM pairs
WHERE prev_ns IS NOT NULL AND from_me != prev_from
GROUP BY contact
HAVING COUNT(*) > 20
ORDER BY avg_reply_min ASC
LIMIT 15
```

---

# Safari history (`safari_visits`)

Columns:

| column            | type    | notes                                   |
|-------------------|---------|-----------------------------------------|
| `visit_id`        | long    | `history_visits.id`                     |
| `visit_ts_cocoa`  | number  | Cocoa-epoch seconds (raw)               |
| `visited_iso`     | string  | ISO-8601 UTC                            |
| `year_month`      | string  |                                         |
| `day_of_week`     | string  |                                         |
| `hour_of_day`     | number  | 0–23 UTC                                |
| `title`           | string  | page title at time of visit             |
| `url`             | string  | full URL                                |
| `domain`          | string  | extracted domain (lowercased)           |
| `domain_raw`      | string  | Safari's `domain_expansion` (fuzzy)     |
| `ok`              | boolean | `load_successful`                       |
| `lifetime_visits` | number  | total visits to this URL                |
| `is_search_result`| boolean | `google.com/url` redirect or `/search?` |

### Top 20 domains you browse

```sql
SELECT domain, COUNT(*) AS visits
FROM safari_visits
WHERE domain IS NOT NULL AND is_search_result = FALSE
GROUP BY domain
ORDER BY visits DESC
LIMIT 20
```

### Peak browsing hour

```sql
SELECT hour_of_day, COUNT(*) AS n
FROM safari_visits
GROUP BY hour_of_day
ORDER BY hour_of_day
```

### Most-visited individual URLs

```sql
SELECT title, url, lifetime_visits
FROM safari_visits
WHERE lifetime_visits > 5
GROUP BY url
ORDER BY lifetime_visits DESC
LIMIT 20
```

### Search-vs-direct navigation

```sql
SELECT is_search_result,
       COUNT(*) AS n,
       ROUND(100.0 * COUNT(*) / (SELECT COUNT(*) FROM safari_visits), 1) AS pct
FROM safari_visits
GROUP BY is_search_result
```

### Failed loads per domain (broken-site detector)

```sql
SELECT domain,
       SUM(CASE WHEN ok = FALSE THEN 1 ELSE 0 END) AS failed,
       COUNT(*) AS total
FROM safari_visits
WHERE domain IS NOT NULL
GROUP BY domain
HAVING failed > 3
ORDER BY failed DESC
LIMIT 20
```

### Browsing volume trend (monthly)

```sql
SELECT year_month, COUNT(*) AS visits, COUNT(DISTINCT domain) AS unique_domains
FROM safari_visits
GROUP BY year_month
ORDER BY year_month DESC
LIMIT 12
```

### Titles matching a keyword (research trail reconstruction)

```sql
SELECT visited_iso, title, url
FROM safari_visits
WHERE title LIKE '%kubernetes%'
ORDER BY visit_ts_cocoa DESC
LIMIT 30
```

---

# Apple Photos (`photos_assets`)

Columns:

| column          | type    | notes                                     |
|-----------------|---------|-------------------------------------------|
| `id`            | long    | `ZASSET.Z_PK`                             |
| `taken_iso`     | string  | ISO-8601 (from ZDATECREATED)              |
| `year_num`      | number  | 2026, 2025, …  (bare `year` conflicts with a mesh SQL reserved word) |
| `year_month`    | string  |                                           |
| `day_of_week`   | string  |                                           |
| `hour_of_day`   | number  | 0–23 UTC                                  |
| `filename`      | string  | `IMG_1234.HEIC`                           |
| `uti`           | string  | `public.heic`, `public.mpeg-4`, …         |
| `kind_name`     | string  | `photo` or `video`                        |
| `width`         | number  | pixels                                    |
| `height`        | number  | pixels                                    |
| `megapixels`    | number  | derived                                   |
| `aspect_ratio`  | number  | derived (w/h)                             |
| `orientation`   | string  | `landscape` / `portrait` / `square`       |
| `duration`      | number  | seconds (video only)                      |
| `lat` / `lng`   | number  | GPS (may be 0 or null)                    |
| `has_location`  | boolean | GPS present + non-zero                    |
| `is_portrait_mode` | boolean | shot with depth (portrait mode)        |
| `is_hdr`        | boolean |                                           |
| `is_favorite`   | boolean | heart-flagged                             |
| `is_trashed`    | boolean | in the recently-deleted bin               |

### Photos per year

```sql
SELECT year, COUNT(*) AS n
FROM photos_assets
WHERE is_trashed = FALSE AND year_num IS NOT NULL
GROUP BY year_num
ORDER BY year_num DESC
```

### Photo vs video split

```sql
SELECT kind_name, COUNT(*) AS n,
       ROUND(SUM(CASE WHEN kind_name='video' THEN duration ELSE 0 END) / 3600.0, 2) AS total_video_hours
FROM photos_assets
WHERE is_trashed = FALSE
GROUP BY kind_name
```

### Peak hour for photo-taking (UTC)

```sql
SELECT hour_of_day, COUNT(*) AS n
FROM photos_assets
WHERE is_trashed = FALSE AND kind_name = 'photo'
GROUP BY hour_of_day
ORDER BY hour_of_day
```

### Camera hardware distribution (by UTI)

```sql
SELECT uti, COUNT(*) AS n
FROM photos_assets
WHERE is_trashed = FALSE
GROUP BY uti
ORDER BY n DESC
```

### Highest-megapixel shots

```sql
SELECT taken_iso, filename, megapixels, width, height
FROM photos_assets
WHERE megapixels IS NOT NULL AND NOT is_trashed
ORDER BY megapixels DESC
LIMIT 15
```

### Portrait-mode adoption over time

```sql
SELECT year, SUM(CASE WHEN is_portrait_mode THEN 1 ELSE 0 END) AS portraits, COUNT(*) AS total
FROM photos_assets
WHERE is_trashed = FALSE AND kind_name = 'photo'
GROUP BY year_num
ORDER BY year_num DESC
```

### Location-tagged vs untagged ratio

```sql
SELECT has_location, COUNT(*) AS n,
       ROUND(100.0 * COUNT(*) / (SELECT COUNT(*) FROM photos_assets WHERE is_trashed = FALSE), 1) AS pct
FROM photos_assets
WHERE is_trashed = FALSE
GROUP BY has_location
```

### Longest videos

```sql
SELECT taken_iso, filename,
       ROUND(duration / 60.0, 2) AS minutes,
       width || 'x' || height AS resolution
FROM photos_assets
WHERE kind_name = 'video' AND NOT is_trashed
ORDER BY duration DESC
LIMIT 10
```

### Favorites density per month (what were the highlights of each month?)

```sql
SELECT year_month, COUNT(*) AS total, SUM(is_favorite) AS favorites
FROM photos_assets
WHERE is_trashed = FALSE
GROUP BY year_month
HAVING favorites > 0
ORDER BY year_month DESC
LIMIT 12
```

---

# Screen Time / knowledgeC (`screentime_events`)

Columns:

| column           | type    | notes                                     |
|------------------|---------|-------------------------------------------|
| `id`             | long    | `ZOBJECT.Z_PK`                            |
| `start_ts_cocoa` | number  | Cocoa-epoch seconds (raw)                 |
| `end_ts_cocoa`   | number  | Cocoa-epoch seconds (raw)                 |
| `started_iso`    | string  | ISO-8601 UTC                              |
| `year_month`     | string  |                                           |
| `day_of_week`    | string  |                                           |
| `hour_of_day`    | number  | 0–23 UTC                                  |
| `app_bundle`     | string  | `com.apple.MobileSMS`, `com.google.Chrome`|
| `app_category`   | string  | rough — Apple / Chrome / Slack / …        |
| `duration_sec`   | number  | session length in seconds                 |
| `stream`         | string  | always `/app/usage` (filtered)            |
| `source_id`      | number  |                                           |

### Top 20 apps by total hours (last 30 days)

```sql
SELECT app_bundle,
       ROUND(SUM(duration_sec) / 3600.0, 2) AS hours,
       COUNT(*) AS sessions
FROM screentime_events
WHERE started_iso > date('now', '-30 days')
GROUP BY app_bundle
ORDER BY hours DESC
LIMIT 20
```

### Apps by category rollup

```sql
SELECT app_category,
       ROUND(SUM(duration_sec) / 3600.0, 2) AS hours,
       COUNT(*) AS sessions
FROM screentime_events
GROUP BY app_category
ORDER BY hours DESC
```

### Focus vs distraction hours by hour-of-day

Separates "work" (JetBrains + Microsoft + Slack) from "browsing"
(Chrome + Safari + Firefox) buckets.

```sql
SELECT hour_of_day,
       ROUND(SUM(CASE WHEN app_category IN ('JetBrains','Microsoft','Slack','Meetings')
                       THEN duration_sec ELSE 0 END) / 60.0, 1) AS work_min,
       ROUND(SUM(CASE WHEN app_category IN ('Chrome','Safari','Firefox')
                       THEN duration_sec ELSE 0 END) / 60.0, 1) AS browser_min
FROM screentime_events
WHERE started_iso > date('now', '-14 days')
GROUP BY hour_of_day
ORDER BY hour_of_day
```

### Longest single sessions

```sql
SELECT started_iso, app_bundle,
       ROUND(duration_sec / 60.0, 1) AS minutes
FROM screentime_events
WHERE duration_sec > 60
ORDER BY duration_sec DESC
LIMIT 20
```

### Session count by day-of-week

```sql
SELECT day_of_week,
       COUNT(*) AS sessions,
       ROUND(SUM(duration_sec) / 3600.0, 1) AS hours
FROM screentime_events
GROUP BY day_of_week
```

### App switching rate per hour (proxy for context-switching cost)

```sql
SELECT hour_of_day,
       COUNT(*) AS launches,
       ROUND(SUM(duration_sec) / 60.0, 1) AS total_min,
       ROUND(1.0 * SUM(duration_sec) / COUNT(*), 1) AS avg_session_sec
FROM screentime_events
GROUP BY hour_of_day
ORDER BY hour_of_day
```

### Slack / meetings vs deep-work ratio, monthly trend

```sql
SELECT year_month,
       ROUND(SUM(CASE WHEN app_category IN ('Slack','Meetings') THEN duration_sec ELSE 0 END) / 3600.0, 2) AS comms_hours,
       ROUND(SUM(CASE WHEN app_category = 'JetBrains'            THEN duration_sec ELSE 0 END) / 3600.0, 2) AS ide_hours
FROM screentime_events
GROUP BY year_month
ORDER BY year_month DESC
LIMIT 12
```

---

# Cross-DB correlations

Once multiple tables are registered, you can join across your entire
digital life. Examples:

### Emails received in an hour where you were also on Slack heavily

```sql
SELECT mm.year_month, mm.hour_of_day,
       COUNT(DISTINCT mm.id) AS emails,
       ROUND(SUM(st.duration_sec) / 60.0, 1) AS slack_min
FROM mail_messages mm
JOIN screentime_events st ON st.year_month = mm.year_month
                          AND st.hour_of_day = mm.hour_of_day
WHERE st.app_category = 'Slack'
GROUP BY mm.year_month, mm.hour_of_day
HAVING slack_min > 30 AND emails > 30
ORDER BY emails DESC
LIMIT 20
```

### Days you took the most photos AND texted the most

```sql
SELECT DATE(photos_assets.taken_iso) AS day,
       COUNT(DISTINCT photos_assets.id) AS photos,
       (SELECT COUNT(*) FROM messages_texts mt
        WHERE DATE(mt.sent_iso) = DATE(photos_assets.taken_iso)) AS messages
FROM photos_assets
WHERE year_num > 2025
GROUP BY day
ORDER BY (photos * messages) DESC
LIMIT 20
```

---

# Cheatsheet — running any of these

```bash
# Register once per DB (only need to re-run when your local data grows).
for db in mail messages safari photos screentime; do
    curl -sX POST http://localhost:8085/mesh/jobs/run \
      -H "content-type: application/x-yaml" \
      --data-binary @$db-register.yaml
    sleep 3
done

# Query.
curl -sX POST http://localhost:8085/mesh/queries \
  -H "content-type: application/json" \
  -d '{"sql":"SELECT ... FROM messages_texts ..."}'
```

Or via the UI: **Cluster** → Inventory Matrix → click ▶ Playground on
the table row → snippet library on the left is pre-tuned to that
table's fields.

---

# Mesh SQL quirks worth knowing

While assembling these examples I hit a couple of small surprises in
the mesh SQL engine that trip up otherwise-correct queries:

- **`year` is a reserved word.** Bare `SELECT year FROM …` fails with
  a compile error. That's why the photos register writes `year_num`
  instead. Alias in the query (`AS y`) or use the pre-computed
  column.
- **`NOT bool_col` doesn't parse.** Use `bool_col = FALSE` (or `= 0`
  if the column happens to be a raw INTEGER 0/1 rather than boolean).
  Positive forms work fine: `WHERE is_favorite` is valid.
- **Aggregate without GROUP BY** — `SELECT COUNT(*) FROM t` alone
  isn't supported for distribution; the engine wants either an
  explicit `GROUP BY` (even if it's a trivial one) or a local
  execution mode. Workaround: `SELECT COUNT(*) FROM t GROUP BY 1`.
