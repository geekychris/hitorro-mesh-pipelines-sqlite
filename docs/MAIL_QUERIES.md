# Mail queries cheatsheet

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
