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
| JDK | 25 — what CI builds and tests on. 17 or newer works locally. |
| Android SDK | Platform 37 (or 36) and matching build-tools |
| Gradle | Provided by the wrapper — `./gradlew` |

```bash
cp local.properties.example local.properties   # then set sdk.dir
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:ktlintCheck :app:lintDebug
```

`local.properties` is git-ignored and is the only file that ever holds a secret.

### Getting an APK without building one

Every pull request builds a debug APK and attaches it to the run. Open the pull request's
Checks tab, pick the **APK** workflow, and the `app-debug-<branch>` artifact at the foot of
the run summary is an installable build of that branch's newest commit. It is kept for 14
days.

It is a debug build — unshrunk, debuggable, signed with the shared debug key, and with BGG
features switched off because CI holds no token. Fine for putting a change on a device to
look at; not something to hand anybody as a release.

Because the debug key is `app/debug.keystore` rather than one each machine makes up, every
build installs over any other — a later APK, an earlier one, or your own local build —
without uninstalling first, so the app data you set up to test with survives.

### The APK on a release

Publishing a GitHub release builds a **release** APK — shrunk, not debuggable, signed with a
key only this repository's secrets hold — and attaches it to that release as
`board-game-nation-<version>.apk`. That is the build for a phone you keep data on; the
per-pull-request artifact above is for looking at a change.

The version comes from the tag. `v0.2.0` produces an APK reporting 0.2.0 under
Settings → About with a `versionCode` of 200 — `major × 10000 + minor × 100 + patch` — so
every release installs over the last as an upgrade. A tag that is not a version fails the
workflow instead of shipping an APK that misreports which build it is.

BGG features are off in a release APK, and more deliberately than in the CI debug one:
`BuildConfig` fields are plain strings inside an APK and this APK is downloadable by anyone,
so a token supplied to the build would be a published token. Build locally with your own
`local.properties` to have BGG on your own device.

A release APK and a CI debug APK share an application id but are signed with different keys,
so Android will not install either over the other. Moving between them means uninstalling
first, which takes the app's data with it — pick one for a device that matters.

#### Signing a release

The debug key is committed because it guards nothing. The release key guards everything: it
is what stops somebody else publishing an update over your install. None of the four values
below is looked up anywhere — you invent them here, once, generating a keystore you then keep
somewhere you will still have it in five years.

Pick a long password first. `-validity 10000` is about twenty-seven years, because a
certificate that has expired is one you can no longer sign an update with.

```bash
keytool -genkeypair -v -keystore release.keystore -alias board-game-nation \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.keystore        # macOS: base64 -i release.keystore
```

Then set four repository secrets under **Settings → Secrets and variables → Actions**:

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE` | The base64 of `release.keystore` |
| `RELEASE_KEYSTORE_PASSWORD` | The password `keytool` asked for |
| `RELEASE_KEY_ALIAS` | `board-game-nation`, or whatever `-alias` you chose |
| `RELEASE_KEY_PASSWORD` | The same password again — see below |

`keytool` has written PKCS12 keystores since JDK 9, and one PKCS12 password covers the key
as well: pass `-keypass` something different and it tells you so and discards it — *"Different
store and key passwords not supported for PKCS12 KeyStores"*. Gradle asks for both regardless,
so the two secrets hold the same value. Different ones fail at signing, after the build.

The workflow checks all four are present before it builds, so a missing one costs seconds
rather than a full Gradle run. Losing the keystore is the expensive mistake: every release
after it carries a different signature, and installing one means uninstalling the app and
its data first.

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
│   ├── share/        How a result is arranged for a picture. Pure Kotlin.
│   ├── timer/        The dual-timer state machine. Pure Kotlin.
│   ├── achievement/  The rule engine
│   ├── stats/        Streaks
│   └── usecase/      Operations that span repositories
├── share/            Draws a result card and hands it to the system share sheet
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

### A first-player win rate is meaningless without a baseline

Going first wins 45% of the time. That is either a serious imbalance or a serious
disadvantage, and which one depends entirely on how many people were at the table — 45% is
a rout among five players and a losing record between two.

So the first-player figure is never shown alone. Every qualifying play contributes what the
first seat would have won there by chance, its winners divided by its players, and the
average of those is drawn beside the actual rate. The gap between the two is the finding,
and it survives a history that mixes table sizes, which every real one does.

Four kinds of play are excluded, each because including it would manufacture an advantage
that is not there or hide one that is: co-op, where the table wins together and the seat
says nothing; abandoned plays, which nobody won; solo plays, where the only player also went
first and would report a permanent 100%; and plays with no winner recorded, which would drag
the rate down for want of data rather than for want of an advantage.

The figure is worth reading per game. A collection-wide number averages a heavy euro
together with a filler and describes neither.

### Where you sat is not when you played

Both are a number per player on a session, and they are deliberately two columns.

A turn order is a line with a head. Someone went first, someone went last, and the
useful question it answers -- who started -- is about that head. A seating is a ring with
neither: what it answers is who was *beside* whom, and the player in the last chair is
next to the player in the first. That wrap is the entire point. 7 Wonders passes cards to
whoever is physically on your left, Bang! measures range round the table, and in both the
arrangement decides the game while who started barely registers.

They coincide at plenty of tables and part company at plenty of others, because a first
player rotates every round while nobody moves chairs. So neither is read off the other,
and a play can carry one, both or neither.

The asymmetry between them shows up in what a partial answer is worth. Half a turn order
is real information: "Aina started" is the common case and stands on its own, which is why
the quick log asks for exactly that and nothing else. Half a seating is worth nothing,
because an unseated player may have been sitting between the two you would otherwise call
neighbours -- so neighbours are reported only once every player has a chair, and withheld
rather than approximated until then. A table of two is not an edge case there: both sides
really are the same person, which is why 7 Wonders starts at three.

The timer is the one place the app fills a seating in for you. Its rotation passes round
the list it was given and wraps back to the top, and a direction-reversal effect sends it
the other way, so what was entered there is a ring rather than a queue -- the setup screen
says so, and the session form opens the moment the clock stops if the table wants to
correct it.

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

### A play does not remember the scoring mode it was logged under

A session records whether it was cooperative and what each player scored, but not its
scoring mode. `SessionRepository.loadForm` works that out again on every read: a co-op
play is a co-op, a play with sides on it was a team game whatever the game says now, and
anything else takes the game's mode as it stands today.

That is the right default — scoring is a property of the game, and one play should not
pin it — but it means a play's mode moves under it. Changing the scoring on any single
play writes the new mode back onto the game, and every earlier play of that game then
reads back under it.

So a field that only makes sense in one mode cannot be settled once, when the play is
written. Both are cleared on save, so rows stop accumulating values no screen shows a
field for. Scores are cleared on load as well, because the mode that justified keeping
them can change afterwards without the row being touched. That also rules out repairing
old rows with a migration — it would have had to ask each play what mode it was in, and
got back whatever its game happened to say the day it ran.

Sides are the one thing not cleared on load, and deliberately: they are what the derivation
above *reads*. A saved side is meant to outrank the game's current mode, so discarding one
on the way out would defeat the rule it feeds. Clearing them on save is enough, and it has
to happen — a side that survives a mode change re-answers the question on every load, which
is how team scoring became a mode a play could not be moved off (#44).

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

### A shared result is a picture, and pictures leave

Sharing a play renders a 1080x1920 card and hands it to the system chooser. Three
decisions in it are worth knowing about.

It is drawn on a canvas rather than composed. The card is never on screen, and rendering
a composable to a bitmap means attaching it to a window and waiting for a frame -- a
lifecycle a ViewModel does not have, producing an image whose size depends on the phone
that drew it. `share/ShareCardRenderer.kt` is a pure function of a `ShareCard` instead:
same play, same picture, on any device, off the main thread. The arrangement it draws --
who leads, who is highlighted, what the headline says -- is `domain/share/`, pure Kotlin
and tested without a device.

It ignores the theme, both the wallpaper and light or dark. The image outlives the phone
it was made on: it lands in a group chat, on a story, in somebody else's camera roll. A
card that looked like the sender's home screen would make the same app's results arrive
looking like a different app every time.

Gold means exactly one thing on it. The winner's row outline, their placement disc, their
score, the headline -- and nothing else is allowed to spend it. The card marks other
facts too: a player's first play of the game gets a tag beside their name, because the
newcomer at the table is half of what the group talks about afterwards. That tag is
outlined in the card's badge style rather than picked out in the accent, since a second
gold element one row down reads as a second winner.

The file itself is a throwaway in `cacheDir/share`, replaced on every share and exposed
through a `FileProvider` scoped to exactly that directory -- naming the cache root would
have put the BGG image cache one guessed filename away from any app that received a
share.

### BGG is treated as somebody else's service

Requests are serialised through a single permit with a minimum two-second gap. Community
consensus puts the ceiling near two requests a second, but a personal collection tracker
has no reason to go near that. `thing` responses are cached for 30 days, images are
downloaded once into app-private storage and never fetched at scroll time, and the
`collection` endpoint's `202` queueing is handled with backoff and visible progress rather
than a spinner that says nothing.

---

## Linting

```bash
./gradlew :app:ktlintFormat              # fix what can be fixed
./gradlew :app:ktlintCheck :app:lintDebug
```

Two linters, and they answer different questions. **ktlint** is about formatting only —
trailing commas, wrapping, import order — and reads its rules from `.editorconfig`, which
Android Studio reads too, so a file saved in the IDE is a file ktlint accepts. Almost
everything it reports, `ktlintFormat` fixes for you.

**Android Lint** is about the code: a permission that may be denied at runtime, a locale
read in a way that will not recompose. It had never been run here, so it opened with 16
errors and 111 warnings that predate it. Those live in `app/lint-baseline.xml`, which
means CI fails on a *new* error rather than on the backlog. Fixing one is a matter of
deleting its entry from that file; regenerating the whole file (delete it and run
`lintDebug`) hides the backlog again, so do that only when the errors are genuinely gone.

Warnings never fail the build. The `Lint` workflow runs both on every pull request,
attaches the HTML reports to the run, and annotates the diff with what it found.

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

331 tests, run on the JVM. The database tests use a real Room in-memory database through
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
| `TurnOrderTest` | Gaps closed, one first player only, partial orders left partial |
| `SeatingTest` | That the ring closes, and that a half-seated table reports no neighbours |
| `FirstPlayerWinRateTest` | The chance baseline across table sizes, and which plays are excluded from it |
| `StreaksTest` | Day, week and month runs, including across a year boundary |
| `CsvTest` | RFC 4180 quoting, embedded newlines, locale-independent numbers |
| `GameQueryBuilderTest` | That values are bound and never interpolated |
| `MigrationChainTest` | That a version bump without a migration fails the build |
| `MigrationTest` | The chain run against a real v1 database: designers backfilled, ids not rewound |
| `SuddenDeathTest` | Placement without scores, and that logging a play never rewrites the game |
| `QuickLogViewModelTest` | That a quick log leaves the game's scoring mode alone |
| `LegacyCsvImportTest` | An archive from before designers were tags still imports intact |
| `ShareCardTest` | Winners lead the card, sides stay whole, an unrecorded result stays unannounced |
| `ShareCardRendererTest` | That the drawing survives twelve players, unreadable lengths and an empty play |

### Sample data

Debug builds have **Settings → Data → Generate sample data**: 40 games with varied
mechanics, weights and prices, 8 players, 200 plays clustered onto weekends across two
years, two rubrics and 25 rated games. Three quarters of the plays record a turn order and
two thirds a seating, and the two disagree where both are present -- a fixture that always
agreed with itself would never catch a screen reading one where it meant the other. Enough to exercise every statistic and every
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
accounts, social features, Play Store distribution, analytics, crash reporting, and
writing plays back to BoardGameGeek.

Sharing a result is the one thing that crossed over, and only in the sense the OS means
it: a picture, drawn on the device, handed to the share sheet. No account, no server,
nothing uploaded.

Powered by [BoardGameGeek](https://boardgamegeek.com).
