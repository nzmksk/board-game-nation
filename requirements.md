# Board Game Tracker — Build Specification

**Target:** Native Android app, personal use, not distributed via Play Store.
**Audience of this document:** an AI coding agent implementing the app end to end.

---

## 1. Overview

A single-user, offline-first Android application for managing a personal board game
collection, logging play sessions, running an in-game dual timer, tracking statistics,
and unlocking achievements.

The app replaces a spreadsheet-based workflow. All data lives on-device in SQLite.
The only network dependency is the BoardGameGeek (BGG) XML API2, used to enrich
game metadata on import. The app must be fully functional with no network connection
apart from that one import path.

### Design principles

1. **Offline-first.** Every read and write hits the local database. Network is
   optional and only ever used to enrich data the user explicitly asked to import.
2. **No accounts, no server, no sync.** There is exactly one user on exactly one device.
3. **Data is portable.** The user must always be able to get their data out via CSV
   and get it back in. Nothing is locked in the app.
4. **Fast at the table.** Logging a session and starting a timer are the two highest-
   frequency actions and must be reachable in as few taps as possible.

### Non-goals

- Multi-device sync, cloud backup, user accounts, sharing, or social features.
- Play Store distribution, in-app purchases, analytics, or crash reporting SDKs.
- Writing data back to BGG (no play sync to a BGG account).

---

## 2. Technology stack

| Concern | Choice | Notes |
|---|---|---|
| Language | Kotlin | |
| Min SDK | 26 (Android 8.0) | Needed for `java.time` without desugaring hassle |
| Target SDK | Latest stable | |
| UI | Jetpack Compose + Material 3 | Single-activity |
| Navigation | Navigation Compose | Type-safe routes |
| Database | Room over SQLite | Migrations must be explicit, no destructive fallback |
| Async | Coroutines + Flow | DAOs return `Flow` so UI reacts to DB changes |
| DI | Hilt | |
| HTTP | OkHttp + Retrofit | BGG calls only |
| XML parsing | `SimpleXmlConverterFactory` or manual `XmlPullParser` | BGG returns XML, not JSON |
| CSV | Manual writer + a small parser (e.g. `kotlin-csv`) | RFC 4180 compliant |
| Timer service | Foreground service + `SystemClock.elapsedRealtime()` | Must survive screen-off |
| Testing | JUnit + Turbine for Flows, Room in-memory DB for DAO tests | |

**Architecture:** MVVM with a repository layer. `UI → ViewModel → Repository → DAO`.
No Android framework types below the repository layer. Domain models are separate
from Room entities where the shapes diverge.

---

## 3. Data model

All IDs are `Long` autoincrement primary keys unless stated. All timestamps are
stored as epoch milliseconds (UTC) in `INTEGER` columns. All dates without a time
component are stored as ISO-8601 `TEXT` (`YYYY-MM-DD`).

### 3.1 `games`

The core collection table.

| Column | Type | Notes |
|---|---|---|
| `id` | INTEGER PK | |
| `bgg_id` | INTEGER NULL | BGG thing ID; null for hand-entered games |
| `title` | TEXT NOT NULL | |
| `year_published` | INTEGER NULL | |
| `min_players` | INTEGER NULL | |
| `max_players` | INTEGER NULL | |
| `best_player_count` | TEXT NULL | Free text, e.g. "3-4" |
| `min_playtime_minutes` | INTEGER NULL | |
| `max_playtime_minutes` | INTEGER NULL | |
| `weight` | REAL NULL | BGG complexity 1.0–5.0 |
| `bgg_rating` | REAL NULL | Geek rating at import time |
| `publisher` | TEXT NULL | |
| `thumbnail_path` | TEXT NULL | Local file path, not a URL |
| `date_added` | TEXT NOT NULL | ISO date the game entered the collection |
| `price` | REAL NULL | Purchase price |
| `currency` | TEXT NOT NULL DEFAULT 'MYR' | |
| `purchase_note` | TEXT NULL | Where bought, condition, etc. |
| `status` | TEXT NOT NULL | Enum: `OWNED`, `WISHLIST`, `PREORDERED`, `SOLD`, `LENT_OUT` |
| `wishlist_priority` | INTEGER NULL | 1–5, only meaningful when status = WISHLIST |
| `in_possession` | INTEGER NOT NULL DEFAULT 1 | 0 when physically lent out |
| `lent_to` | TEXT NULL | Person's name |
| `lent_date` | TEXT NULL | |
| `is_expansion` | INTEGER NOT NULL DEFAULT 0 | |
| `base_game_id` | INTEGER NULL FK → games.id | Set when `is_expansion` = 1 |
| `sudden_death_possible` | INTEGER NOT NULL DEFAULT 0 | Game can end before final scoring |
| `notes` | TEXT NULL | |
| `created_at` / `updated_at` | INTEGER NOT NULL | |

Indexes: `bgg_id` (unique where not null), `title`, `status`, `base_game_id`.

### 3.2 `tags` and `game_tags`

Mechanics, categories and designers are all tags, distinguished by `kind`. Modelling
them as a join rather than columns means BGG's long mechanic lists don't require schema
changes, and it makes every one of them filterable and groupable on equal terms.

**`tags`:** `id`, `name` TEXT NOT NULL, `kind` TEXT NOT NULL
(`MECHANIC` | `CATEGORY` | `DESIGNER` | `CUSTOM`).
Unique index on `(name, kind)`.

**`game_tags`:** `game_id` FK, `tag_id` FK, composite PK, `ON DELETE CASCADE`.

### 3.3 `players`

Local-only player profiles. No authentication, no linkage to any account.

| Column | Type | Notes |
|---|---|---|
| `id` | INTEGER PK | |
| `name` | TEXT NOT NULL UNIQUE | |
| `is_self` | INTEGER NOT NULL DEFAULT 0 | Exactly one row should have this set |
| `color_hex` | TEXT NULL | For charts and timer UI |
| `notes` | TEXT NULL | |
| `archived` | INTEGER NOT NULL DEFAULT 0 | Hide from pickers without deleting history |

Seed a single `is_self = 1` row on first launch via an onboarding prompt for the
user's name.

### 3.4 `sessions`

One row per play.

| Column | Type | Notes |
|---|---|---|
| `id` | INTEGER PK | |
| `game_id` | INTEGER NOT NULL FK | |
| `played_on` | TEXT NOT NULL | ISO date |
| `started_at` | INTEGER NULL | Epoch ms, set when timed live |
| `ended_at` | INTEGER NULL | |
| `duration_minutes` | INTEGER NOT NULL | Actual elapsed, auto-filled if timed, editable |
| `player_count` | INTEGER NOT NULL | Denormalised for fast stats |
| `location` | TEXT NULL | |
| `is_cooperative` | INTEGER NOT NULL DEFAULT 0 | |
| `coop_outcome` | TEXT NULL | `WIN` \| `LOSS` \| `NA` — for co-op games |
| `end_condition` | TEXT NULL | `SUDDEN_DEATH`; null means played to final scoring |
| `end_reason` | TEXT NULL | Free text, e.g. "Military supremacy" |
| `is_incomplete` | INTEGER NOT NULL DEFAULT 0 | Game abandoned before finishing |
| `is_teaching_game` | INTEGER NOT NULL DEFAULT 0 | Someone was learning; skews duration stats |
| `notes` | TEXT NULL | |
| `created_at` / `updated_at` | INTEGER NOT NULL | |

Indexes: `game_id`, `played_on`.

### 3.5 `session_players`

| Column | Type | Notes |
|---|---|---|
| `id` | INTEGER PK | |
| `session_id` | INTEGER NOT NULL FK, CASCADE | |
| `player_id` | INTEGER NOT NULL FK | |
| `score` | REAL NULL | Nullable — many games have no score |
| `placement` | INTEGER NULL | 1 = winner; ties share a placement |
| `is_winner` | INTEGER NOT NULL DEFAULT 0 | Explicit, since not all games rank by score |
| `faction` | TEXT NULL | Role, character, colour, faction |
| `is_new_player` | INTEGER NOT NULL DEFAULT 0 | First time this player played this game |
| `turn_time_ms` | INTEGER NULL | Populated by the timer, if used |
| `bank_time_remaining_ms` | INTEGER NULL | Populated by the timer, if used |

Unique index on `(session_id, player_id)`.

### 3.6 Rubric ratings

The user's rating system uses different criteria per game category (Strategy,
Cooperative, Social Deduction, Party, Filler/Light Card, Expansion). Do **not** model
criteria as columns — new criteria must not require a migration.

**`rubrics`:** `id`, `name` TEXT (e.g. "Strategy"), `description` TEXT NULL, `archived` INTEGER.

**`rubric_criteria`:** `id`, `rubric_id` FK, `name` TEXT, `description` TEXT NULL,
`weight` REAL NOT NULL DEFAULT 1.0, `max_score` REAL NOT NULL DEFAULT 10.0,
`sort_order` INTEGER.

**`game_ratings`:** `id`, `game_id` FK, `rubric_id` FK, `rated_on` TEXT,
`computed_score` REAL, `notes` TEXT NULL. A game may have more than one rating row
over time — keep them all and treat the newest as current, so re-evaluations are
visible as history.

**`game_rating_scores`:** `id`, `game_rating_id` FK CASCADE, `criterion_id` FK,
`score` REAL NOT NULL.

`computed_score` = `Σ(score × weight) / Σ(weight × max_score) × 10`, recomputed on
every write and stored so list sorting doesn't require a join-heavy aggregate.

### 3.7 `achievements` and `achievement_unlocks`

**`achievements`:** `id`, `code` TEXT UNIQUE, `name` TEXT, `description` TEXT,
`icon` TEXT, `category` TEXT, `target_value` REAL NULL, `is_hidden` INTEGER,
`sort_order` INTEGER. Seeded from a bundled JSON asset on first run and reconciled
on version upgrade (insert new codes, update text, never delete unlocks).

**`achievement_unlocks`:** `id`, `achievement_id` FK, `unlocked_at` INTEGER,
`progress_value` REAL, `session_id` INTEGER NULL FK (the session that triggered it).
Unique index on `achievement_id`.

### 3.8 `timer_presets`

`id`, `name` TEXT, `turn_seconds` INTEGER, `bank_seconds` INTEGER,
`warning_threshold_seconds` INTEGER, `sound_enabled` INTEGER, `haptics_enabled` INTEGER,
`auto_pass_on_bank_empty` INTEGER, `game_id` INTEGER NULL FK (preset bound to a specific game).

---

## 4. Features

### 4.1 Collection management

- **List view** with search-as-you-type on title, plus filter chips: status, player
  count (games playable with exactly N), playtime bucket, mechanic, category, rated/unrated,
  in-possession. Sort by title, date added, play count, rating, price, cost-per-play.
- **Grid/list toggle**, thumbnails from the locally cached BGG image.
- **Detail screen:** metadata, tags, play history for that game, aggregate stats
  (plays, total hours, win rate, average duration vs BGG's stated range, cost-per-play),
  rubric rating breakdown, linked expansions, lend status.
- **Manual add/edit** for everything, including games not on BGG (prototypes,
  print-and-play). BGG data is a convenience, never a requirement.
- **Expansions** are `games` rows with `is_expansion = 1` and a `base_game_id`. On the
  base game's detail screen, show owned expansions. When logging a session, allow
  selecting which expansions were in play (see 4.3).
- **Lending tracker:** mark a game lent to a person with a date; a badge appears in
  the list and a reminder is available for games out longer than a configurable
  threshold (default 30 days).
- **Wishlist:** same table, `status = WISHLIST`, with a priority. Excluded from
  collection stats and value totals.
- **Bulk actions:** multi-select for tagging, status change, and delete.

### 4.2 BGG integration

**Endpoints (base `https://boardgamegeek.com/xmlapi2/`):**

- `search?query={q}&type=boardgame,boardgameexpansion` — name search, returns IDs.
- `thing?id={id1,id2,...}&stats=1` — full metadata. Batch up to 20 IDs per request.
- `collection?username={u}&own=1&stats=1` — bulk import of an existing BGG collection.
  This endpoint queues: a `202 Accepted` means "retry shortly". Implement exponential
  backoff starting at 3 seconds, max 8 attempts, and surface progress in the UI.

**Authorization — read this carefully:**

As of the 2025-07-02 policy revision, <cite index="11-1">BGG requires registration and authorization for nearly all use of the XML API; applications are registered at boardgamegeek.com/applications, and approval may take a week or more</cite>. <cite index="11-1">Approved applications create Bearer tokens and must send them in an `Authorization: Bearer {token}` header, with requests going to `boardgamegeek.com` without a leading `www`</cite>. <cite index="11-1">A purely non-commercial application is generally eligible for a free non-commercial license</cite>.

There is one relevant carve-out: <cite index="11-1">downloading your own collection while logged in does not require registration</cite>.

**Implementation requirement:** store the token in `local.properties` → `BuildConfig`,
never hardcoded in source and never committed. If no token is configured, the app
must still build and run with BGG features disabled and a clear message in Settings
explaining how to add one. Manual entry and CSV import must cover every field BGG
would have supplied, so the app is never blocked on API access.

**Rate limiting and caching:**

- Serialise all BGG calls through a single-permit semaphore with a minimum 2-second
  gap between requests. Community consensus and library implementations converge on
  roughly <cite index="1-1">2 requests per second as the ceiling</cite>, but this app has no reason to go
  anywhere near that — be conservative, this is a personal tool.
- Set a descriptive `User-Agent`.
- Retry on `429` and `5xx` with exponential backoff; give up after 5 attempts and
  surface a retryable error, never a silent failure.
- Cache every `thing` response body to disk keyed by BGG ID with a 30-day TTL. Never
  refetch metadata the app already has unless the user pulls to refresh.
- Download the thumbnail once, store it in app-private storage, and reference the
  local path. Never render directly from a remote URL at list-scroll time.

### 4.3 Session logging

Two entry paths:

1. **Quick log** — game, date, duration, players, winner. Under 20 seconds to complete.
   Defaults: today's date, the player set from the most recent session of that game,
   duration prefilled from the game's average actual duration.
2. **Full log** — everything in `sessions` and `session_players`, including scores,
   placements, factions, expansions used, location, notes, and a photo attachment.

Behaviour:

- Player picker offers recently-played-with players first, then all, with inline
  "add new player".
- Scoring mode is per-game and remembered: **ranked scores** (enter numbers, placements
  derive automatically, highest or lowest wins configurable), **manual placement**
  (drag to order), or **co-op** (single win/loss for the table).
- Ties are allowed: two players may share placement 1 and both be winners.
- **Sudden-death endings.** A game flagged `sudden_death_possible` offers an "ended by"
  choice per play. A play that ended that way is ranked by the order the user gives, since
  no final scoring happened; any partial scores are kept but excluded from score averages.
  This is separate from `is_incomplete`, which means abandoned and is excluded from stats.
- **In-progress sessions.** Starting the timer creates a draft session immediately.
  If the app is killed, the draft is recoverable on next launch with a prompt to
  resume, save, or discard. Never lose a night's data to a process death.
- Expansions used are stored in a `session_expansions` join (`session_id`, `game_id`).
- Editing a session recomputes any affected achievement progress.

### 4.4 Dual timer

A chess-clock-style turn timer, per player.

**Model.** Each player has two clocks:

- **Turn timer** — fixed allowance (default 60s), reset to full at the start of every
  one of that player's turns.
- **Bank timer** — a per-player reserve (default 10 minutes). It only drains once the
  current turn timer has hit zero. It is *not* replenished between turns.

**Behaviour spec:**

- Tap the active player's zone to end their turn and pass to the next player. Their
  turn timer resets to full for their next turn; their bank carries over unchanged.
- While a turn timer counts down, the bank is frozen. On reaching zero, the display
  switches to the bank and it begins draining continuously.
- When a player's bank reaches zero: configurable behaviour, either (a) flag the player
  as timed-out and keep counting into negative "overtime" for the record, or (b) auto-pass
  the turn. Default (a) — this is a friendly game aid, not a tournament enforcer.
- Global pause/resume for rules lookups and table talk. Paused time accrues to no one
  and is tracked separately so it can be subtracted from session duration.
- Undo last pass (single level) for misclicks.
- Configurable turn order, including reversing direction mid-game (for games with
  direction-reversal effects) and skipping a player.

**Technical requirements:**

- Use `SystemClock.elapsedRealtime()` deltas, never `System.currentTimeMillis()` and
  never a tick-accumulator. Wall-clock time changes and dropped frames must not
  corrupt elapsed time.
- Run in a **foreground service** with a persistent notification showing the active
  player and remaining time, so timing survives screen-off and app backgrounding.
- Hold a partial wake lock only while running; release on pause and stop.
- Keep the screen on while the timer screen is foregrounded (`FLAG_KEEP_SCREEN_ON`),
  user-disableable.
- Warning at a configurable threshold (default 10s remaining): sound, vibration, and
  colour shift. Respect system silent mode.
- Persist full timer state to the database on every state transition so a process
  kill loses at most one turn.
- On stopping, write per-player `turn_time_ms` (total across all turns) and
  `bank_time_remaining_ms` into `session_players`, and offer to save the session.
- Landscape layout with large, thumb-reachable per-player zones. Player zones colour-coded
  from `players.color_hex`.

### 4.5 Statistics

A dashboard plus per-game and per-player breakdowns. All computed by SQL aggregate
queries returning `Flow`, not in-memory over full table loads.

**Collection:** total games, total value, games by mechanic/category (bar chart),
weight distribution, player-count coverage (how many games support each count),
unplayed games ("shelf of shame"), owned-but-never-rated.

**Plays:** total plays, total hours, plays over time (line chart by month), most-played
games, longest and shortest actual sessions, average actual duration versus BGG's stated
playtime per game (a genuinely interesting divergence and worth surfacing prominently),
plays by day of week, and current/longest streak of consecutive weeks with a play.

**Value:** cost-per-play per game and overall, total spend by year, most and least
economical purchases. This is the metric that most changes buying behaviour, so give
it a first-class card.

**Players:** head-to-head win rates (ranked by the record itself -- most wins first,
then fewest losses -- not by how many plays the pair have shared), win rate per game per
player, most frequent opponents, average score per game per player, and a "nemesis"
(highest win rate against the user).

**Derived metrics:** H-index (N games played at least N times), a common collector
metric worth including.

Excludes: sessions marked `is_incomplete` are omitted from duration averages but
counted in play totals. Sessions marked `is_teaching_game` are flagged separately in
duration stats.

### 4.6 Achievements

A rule engine, not hardcoded `if` statements scattered through the codebase.

**Structure.** Each achievement is defined in a bundled JSON asset with a `code`,
display text, and a rule descriptor. A single `AchievementEvaluator` runs after every
session insert, update, or delete, and after collection changes. It evaluates all
non-unlocked achievements against the current database state and inserts unlock rows.
Evaluation must be idempotent — running it twice never double-unlocks.

**Rule types to support:**

- `COUNT_THRESHOLD` — total plays, total games owned, total distinct games played,
  total hours.
- `PER_GAME_THRESHOLD` — play any single game N times.
- `BREADTH` — play N distinct games, or games spanning N distinct mechanics.
- `STREAK` — play on N consecutive days/weeks.
- `TIME_WINDOW` — N plays within a single day/week/month.
- `ATTRIBUTE` — a session with ≥N players, a session over N hours, a game with weight ≥N.
- `RATIO` — win rate ≥X% over ≥N plays of a game.
- `COLLECTION` — every game in a mechanic played at least once; no unplayed games in
  the collection; cost-per-play under X for any game.

**Seed set (at least 30, spread across categories and difficulty):** examples —
first logged play; 10/50/100/500 total plays; play the same game 10/25/50 times;
play 5 games in one day; play on 7 consecutive days; a session over 3 hours; a
session with 6+ players; own 50 games; play every game in the collection at least
once; get a game's cost-per-play under 10; win 10 in a row; lose 10 in a row; teach
5 different games to new players; play a game with weight ≥4.0; log a play in 12
consecutive months.

**Progress display.** Locked achievements show progress toward `target_value`
(e.g. 34/50 plays). Hidden achievements (`is_hidden = 1`) show as "???" until unlocked.
Unlock surfaces as a non-blocking snackbar or bottom sheet after saving a session —
never a modal that interrupts logging.

### 4.7 CSV import/export

This is the backup mechanism, so it must be complete and lossless.

**Export.** Writes a set of CSVs to a user-chosen directory via the Storage Access
Framework (`ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT_TREE`) — no legacy external
storage permissions. Files: `games.csv`, `tags.csv`, `game_tags.csv`, `players.csv`,
`sessions.csv`, `session_players.csv`, `session_expansions.csv`, `rubrics.csv`,
`rubric_criteria.csv`, `game_ratings.csv`, `game_rating_scores.csv`,
`achievement_unlocks.csv`, and a `manifest.csv` recording schema version and export
timestamp. Optionally bundle all of them into a single timestamped ZIP.

Format: RFC 4180, UTF-8 with BOM (so the files open cleanly in Excel), `\r\n` line
endings, header row required, ISO-8601 dates, `.` decimal separator regardless of locale.

**Import.** Two modes, chosen by the user:

- **Merge** — match on natural keys (`games.bgg_id`, else `games.title`; `players.name`;
  `sessions` on `(game, played_on, player_count)`), update matched rows, insert unmatched.
- **Replace** — wipe all tables and load from file. Requires typed confirmation.

Import must: validate headers before touching the database, run inside a single
transaction, report per-row errors with line numbers without aborting the whole import,
and present a preview summary ("42 games: 3 new, 39 updated; 118 sessions: all new")
for confirmation before committing.

**Also implement full DB backup/restore.** A one-tap export of the raw `.db` file
(after a `WAL` checkpoint) is a more faithful backup than CSV and takes an hour to build.
CSV is for portability and spreadsheet interop; the `.db` copy is for disaster recovery.
Ship both.

**Scheduled backup.** A `WorkManager` weekly job writing a timestamped export to the
user's chosen directory, keeping the last N (default 8) and pruning older ones. This is
the single most valuable feature in this document, because it is the only thing standing
between the user and total data loss when the phone dies.

### 4.8 Settings

Theme (light/dark/system), dynamic colour toggle, default currency, BGG token status
and username, default timer preset, backup directory and schedule, achievement
notification toggle, "self" player identity, data management (export, import, backup,
restore, wipe), and an about screen with the "Powered by BGG" attribution logo if
BGG data is displayed.

---

## 5. Screens

| Screen | Contents |
|---|---|
| Home / Dashboard | Recent sessions, quick-log FAB, quick-timer button, streak card, recently unlocked achievement |
| Collection | Filterable, searchable list/grid of games |
| Game detail | Metadata, stats, play history, rating breakdown, expansions, actions |
| Add/Edit game | Manual form + "Search BGG" affordance |
| BGG import | Username entry, collection fetch with progress, per-game selection |
| Session list | Chronological, filterable by game/player/date range |
| Session detail / edit | Full form |
| Quick log | Compact bottom sheet |
| Timer setup | Player order, preset selection, turn/bank durations |
| Timer running | Landscape, per-player zones, pause, undo, end |
| Stats | Tabbed: Collection / Plays / Value / Players |
| Achievements | Grid, locked with progress bars, unlocked with dates |
| Players | List, add/edit, per-player stats |
| Rubrics | Manage rubrics and criteria; rate a game |
| Settings | As above |

Bottom navigation: Collection, Sessions, Timer, Stats, More.

---

## 6. Implementation milestones

Build in this order; each milestone must be runnable and testable before starting the next.

1. **Foundation** — project setup, Room schema, all entities and DAOs, migrations,
   Hilt wiring, theme, navigation skeleton. DAO unit tests against an in-memory DB.
2. **Collection CRUD** — manual add/edit/delete, list with search and filters, detail
   screen. Fully usable without BGG.
3. **CSV export/import + DB backup** — build this *third*, not last. Once the user
   starts entering real data, backup must already exist.
4. **Session logging** — quick log, full log, session list, per-game play history,
   basic per-game stats on the detail screen.
5. **Dual timer** — foreground service, state machine, persistence, UI, session linkage.
6. **BGG integration** — search, thing fetch, collection import, caching, rate limiting,
   image download.
7. **Statistics** — aggregate queries and charts.
8. **Achievements** — rule engine, seed data, evaluation hooks, UI.
9. **Rubric ratings** — rubric/criteria management, rating entry, computed scores,
   rating-based sorting.
10. **Polish** — empty states, error states, loading skeletons, accessibility labels,
    landscape layouts, scheduled backup job.

---

## 7. Acceptance criteria

- The app functions completely with the device in airplane mode, except BGG import,
  which fails with a clear, retryable message.
- Killing the app mid-session (via the task switcher or a forced process death) loses
  at most one turn of timer data and never loses a saved session.
- A full export followed by a wipe followed by a full import reproduces the database
  exactly: identical row counts across all tables, identical computed statistics, and
  identical achievement unlock states.
- Collection list scrolls at 60fps with 500 games and 5,000 sessions in the database.
- No `SELECT *` over a full table on the main thread. All queries expose `Flow`.
- Room migrations are written and tested for every schema change. `fallbackToDestructiveMigration`
  is never called.
- No hardcoded strings in composables — everything in `strings.xml`.
- The BGG token is absent from the repository and from version control.
- Deleting a game with sessions prompts explicitly and either cascades or is blocked;
  it never leaves orphaned rows.

---

## 8. Seed data for development

Generate a fixture set: 40 games with varied mechanics, weights, and prices; 8 players;
200 sessions spread over 24 months with realistic clustering (more on weekends); a
Strategy rubric with 6 criteria and a Cooperative rubric with 5; ratings on 25 games.
This exercises every stats query and achievement rule without manual data entry.
