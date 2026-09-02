# Board Game Nation

An offline-first Android app for a personal board game collection: what you own, what you
played, how long it actually took, who won, and what any of it cost per play.

Built to the specification in [requirements.md](requirements.md). Single user, single
device, no accounts and no server. The only network call the app ever makes is to
BoardGameGeek, and only when you explicitly ask it to import something.

---

## Building it

### What you need

| | |
|---|---|
| JDK | 21 |
| Android SDK | Platform 37 (or 36) and matching build-tools |
| Gradle | Provided by the wrapper — `./gradlew` |

```bash
cp local.properties.example local.properties   # then set sdk.dir
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

`local.properties` is git-ignored and is the only file that ever holds a secret.

### Getting an APK without building one

Every pull request builds a debug APK and attaches it to the run. Open the pull request's
Checks tab, pick the **APK** workflow, and the `app-debug-<sha>` artifact at the foot of
the run summary is an installable build of exactly that commit. It is kept for 14 days.

It is a debug build — unshrunk, debuggable, signed with the shared debug key, and with BGG
features switched off because CI holds no token. Fine for putting a change on a device to
look at; not something to hand anybody as a release.

### BoardGameGeek access

Since BGG's 2025-07-02 policy revision, the XML API2 needs a registered application and a
bearer token. Register at [boardgamegeek.com/applications](https://boardgamegeek.com/applications)
— a non-commercial application like this one is eligible for a free licence — then add the
token to `local.properties`:

```properties
BGG_API_TOKEN=your-token-here
```

**The app builds and runs perfectly well without one.** BGG features switch themselves
off, Settings explains why, and everything BGG would have supplied can be typed in by hand
or imported from CSV. Nothing in the app is blocked on API access.

The token reaches the code through `BuildConfig` and is never committed.

---

## What is in the box

```
app/src/main/java/com/boardgamenation/tracker/
├── core/time/        Clocks. Wall-clock and monotonic, deliberately separate.
├── data/
│   ├── db/           Room: 17 entities, DAOs, projections, the query builder
│   ├── repository/   The repository layer; nothing above it touches a DAO
│   ├── csv/          RFC 4180 reader and writer, export and import
│   ├── backup/       Raw .db backup, restore, and the weekly WorkManager job
│   ├── bgg/          XML API2 client, pull parser, rate limiter
│   ├── prefs/        DataStore settings
│   └── dev/          Generated fixtures, debug builds only
├── domain/
│   ├── model/        Domain types and the placement rules
│   ├── timer/        The dual-timer state machine. Pure Kotlin.
│   ├── achievement/  The rule engine
│   ├── stats/        Streaks
│   └── usecase/      Operations that span repositories
├── timer/            The foreground service and the singleton that owns the clock
└── ui/               Compose screens, one package per screen
```

`UI → ViewModel → Repository → DAO`, and no Android framework types below the repository
layer.

---

## Decisions worth knowing about

### The timer never counts ticks

A chess clock that loses time is worse than no chess clock. The engine
(`domain/timer/TimerEngine.kt`) is a pure function of a **monotonic** reading:
every displayed number is a stored anchor subtracted from `SystemClock.elapsedRealtime()`.
Nothing is ever decremented on a frame, so a dropped frame, a doze, or the user changing
the system clock cannot make the numbers drift.

That purity is also why the whole of its behaviour is testable by handing it a fake clock
and advancing it — 21 tests, no waiting.

The turn clock absorbs time first and only its overrun reaches the bank, which is the one
rule the dual timer is built around. Banks are allowed to go negative so overtime stays on
the record.

### A killed process costs seconds, not an evening

Starting the clock creates a draft session immediately. State is checkpointed to the
database at every transition *and* on a ten-second interval while running, so a process
death loses seconds rather than a turn. A recovered clock always comes back **paused**:
the stored monotonic anchor cannot be trusted across a process death, and is meaningless
across a reboot, so the honest thing is to hand the table a stopped clock and let them
press resume.

### Statistics are SQL, not Kotlin

Every figure on the Stats screen is an aggregate query returning a `Flow`. Nothing loads a
table to count it. The collection list computes play count, last played, rating and
cost-per-play in SQL specifically because the list sorts by them — sorting in memory would
mean loading the whole collection to sort it.

There is one deliberate exception: streaks. "Consecutive" needs a window function, and
minSdk 26 ships SQLite 3.19, which predates them. The database returns a distinct list of
playing days — a few hundred short strings — and the run arithmetic happens in Kotlin.

### Achievements are data, not code

Every achievement lives in `assets/achievements.json` with a rule descriptor. Adding one
means editing JSON. A single evaluator reads every metric once and tests all rules against
that one snapshot, so an evaluation pass costs a fixed number of queries no matter how many
achievements exist.

Idempotency is structural rather than bookkeeping: unlocks are inserted with
`OnConflictStrategy.IGNORE` against a unique index on `achievement_id`. Evaluating twice
cannot double-unlock. Editing or deleting a session runs `reconcile()`, which withdraws
unlocks the data no longer supports — an undeserved trophy would make the whole screen
untrustworthy.

An unreadable or future-dated rule degrades to "never unlocks" instead of crashing.

### A game that ends early still has a winner

Some games stop the moment a condition is met. 7 Wonders Duel is the obvious one: military
or scientific supremacy ends it before anyone counts a victory point, so there are no final
scores to rank by, but the play unambiguously finished and unambiguously has a winner.

That is a property of the play, not of the game, so it is a nullable `end_condition` on the
session rather than another `ScoringMode`. The game keeps whatever scoring it normally uses
and a flag saying it *can* end this way, which is all that decides whether the logging form
offers the choice. A sudden-death play is ranked by the order the user puts the players in;
any partial score they enter is kept but does not decide it, and those scores are excluded
from average-score statistics because a count taken mid-game is not comparable to a final one.

Deliberately not `is_incomplete`. That flag means abandoned, and it drops a session out of
the win-rate and duration statistics — using it here would erase a legitimate win.

### Both kinds of backup, because they are for different things

CSV is for portability and spreadsheet interop: RFC 4180, UTF-8 with a BOM so Excel does
not mangle accented names, CRLF endings, and a `.` decimal separator regardless of locale.

The raw `.db` copy is for disaster recovery, and is the more faithful of the two — it
carries indices, autoincrement state and every column exactly. A `wal_checkpoint(TRUNCATE)`
runs first so the single file copied is genuinely complete.

Replace-mode import restores primary keys verbatim, which is what makes an
export → wipe → import round trip reproduce the database rather than merely approximate
it. Merge mode ignores incoming ids entirely and matches on natural keys, because ids from
another database mean nothing here.

The weekly `WorkManager` job errs toward running: no network constraint, no charging
requirement, and a retry rather than a failure when the destination is briefly unavailable.

### Chart colours opt out of dynamic colour

The app chrome follows the wallpaper. Chart marks do not. A palette is only legible if its
hues stay separable under colour-vision deficiency and keep contrast against the surface,
and neither property survives being regenerated from an arbitrary image. The eight
categorical slots in `ui/theme/ChartColors.kt` were validated as a set against this app's
own light and dark surfaces, and are assigned to players in fixed order so a player keeps
their colour when the set on screen changes. Nothing is ever encoded by colour alone —
every chart row and every timer zone carries the name beside the swatch.

### BGG is treated as somebody else's service

Requests are serialised through a single permit with a minimum two-second gap. Community
consensus puts the ceiling near two requests a second, but a personal collection tracker
has no reason to go near that. `thing` responses are cached for 30 days, images are
downloaded once into app-private storage and never fetched at scroll time, and the
`collection` endpoint's `202` queueing is handled with backoff and visible progress rather
than a spinner that says nothing.

---

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

183 tests, run on the JVM. The database tests use a real Room in-memory database through
Robolectric rather than mocks, so a query that compiles but returns the wrong rows still
fails.

| Suite | What it covers |
|---|---|
| `TimerEngineTest` | Turn and bank arithmetic, overtime, pause, undo, direction, skipping |
| `CsvRoundTripTest` | Export → wipe → import reproduces row counts, statistics and unlocks |
| `AchievementEvaluatorTest` | Every rule type, idempotency, and withdrawal on reconcile |
| `GameDaoTest` | The assembled collection query, aggregates, cascades, Flow invalidation |
| `SessionDaoTest` | Placement derivation, drafts, prefill, filters |
| `PlacementCalculatorTest` | Ties, golf scoring, co-op |
| `StreaksTest` | Day, week and month runs, including across a year boundary |
| `CsvTest` | RFC 4180 quoting, embedded newlines, locale-independent numbers |
| `GameQueryBuilderTest` | That values are bound and never interpolated |
| `MigrationChainTest` | That a version bump without a migration fails the build |
| `MigrationTest` | The chain run against a real v1 database: designers backfilled, ids not rewound |
| `SuddenDeathTest` | Placement without scores, and that logging a play never rewrites the game |
| `QuickLogViewModelTest` | That a quick log leaves the game's scoring mode alone |
| `LegacyCsvImportTest` | An archive from before designers were tags still imports intact |

### Sample data

Debug builds have **Settings → Data → Generate sample data**: 40 games with varied
mechanics, weights and prices, 8 players, 200 plays clustered onto weekends across two
years, two rubrics and 25 rated games. Enough to exercise every statistic and every
achievement rule without typing anything. The random source is seeded, so the fixture is
reproducible.

---

## Acceptance criteria

| Criterion | Status |
|---|---|
| Works in airplane mode except BGG, which fails retryably | Met — errors carry a `retryable` flag and Retry is only offered when it could help |
| A killed app loses at most one turn, never a saved session | Met — draft session plus per-transition and 10s checkpoints |
| Export → wipe → import reproduces the database exactly | Met and tested (`CsvRoundTripTest`) |
| A backup taken before an update still restores after it | Met and tested (`MigrationTest` for `.db`, `LegacyCsvImportTest` for CSV) |
| No `SELECT *` over a full table on the main thread; all queries expose `Flow` | Met — `allowMainThreadQueries` is never enabled outside tests |
| Migrations written and tested; `fallbackToDestructiveMigration` never called | Met and guarded by `MigrationChainTest` |
| No hardcoded strings in composables | Met — every user-facing string is in `strings.xml` |
| The BGG token is absent from the repository | Met — `local.properties` only, git-ignored |
| Deleting a game with sessions prompts explicitly | Met — the repository returns `NeedsConfirmation` with counts |
| 60fps with 500 games and 5,000 sessions | **Designed for, not measured.** Aggregates are in SQL, lists are keyed, thumbnails are local files. Confirming it needs a device. |

---

## Not built

Deliberately out of scope, per the specification: multi-device sync, cloud backup, user
accounts, sharing, Play Store distribution, analytics, crash reporting, and writing plays
back to BoardGameGeek.

Powered by [BoardGameGeek](https://boardgamegeek.com).
