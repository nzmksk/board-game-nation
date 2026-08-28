package com.boardgamenation.tracker.data.dev

import com.boardgamenation.tracker.core.time.AppClock
import com.boardgamenation.tracker.core.time.DateUtils
import com.boardgamenation.tracker.data.db.dao.GameDao
import com.boardgamenation.tracker.data.db.dao.PlayerDao
import com.boardgamenation.tracker.data.db.dao.RubricDao
import com.boardgamenation.tracker.data.db.dao.SessionDao
import com.boardgamenation.tracker.data.db.dao.TagDao
import com.boardgamenation.tracker.data.db.entity.GameEntity
import com.boardgamenation.tracker.data.db.entity.GameRatingEntity
import com.boardgamenation.tracker.data.db.entity.GameRatingScoreEntity
import com.boardgamenation.tracker.data.db.entity.GameTagCrossRef
import com.boardgamenation.tracker.data.db.entity.PlayerEntity
import com.boardgamenation.tracker.data.db.entity.SessionEntity
import com.boardgamenation.tracker.data.db.entity.SessionExpansionEntity
import com.boardgamenation.tracker.data.db.entity.SessionPlayerEntity
import com.boardgamenation.tracker.data.repository.AchievementRepository
import com.boardgamenation.tracker.data.repository.RubricRepository
import com.boardgamenation.tracker.di.IoDispatcher
import com.boardgamenation.tracker.domain.model.CoopOutcome
import com.boardgamenation.tracker.domain.model.GameStatus
import com.boardgamenation.tracker.domain.model.ScoringMode
import com.boardgamenation.tracker.domain.model.TagKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.random.Random
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A generated collection and play history, for development.
 *
 * The point is to exercise every statistic and every achievement rule without anyone
 * typing two hundred sessions by hand: varied mechanics, weights and prices so the
 * distribution charts have shape, and plays clustered onto weekends over two years so
 * the streak, month and day-of-week views show something other than a flat line.
 *
 * The random source is seeded, so two runs produce the same fixture and a bug found
 * against it can be reproduced.
 */
@Singleton
class DevFixtures @Inject constructor(
    private val gameDao: GameDao,
    private val tagDao: TagDao,
    private val playerDao: PlayerDao,
    private val sessionDao: SessionDao,
    private val rubricDao: RubricDao,
    private val rubricRepository: RubricRepository,
    private val achievementRepository: AchievementRepository,
    private val clock: AppClock,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {

    private val random = Random(seed = 20260315)

    suspend fun generate(): String = withContext(io) {
        rubricRepository.seedDefaultsIfEmpty()

        val playerIds = seedPlayers()
        val gameIds = seedGames()
        val sessionCount = seedSessions(gameIds, playerIds)
        val rated = seedRatings(gameIds)

        // The fixture is only useful if the achievements it earns are on the shelf.
        achievementRepository.reconcile()

        "${gameIds.size} games, ${playerIds.size} players, " +
            "$sessionCount sessions, $rated ratings"
    }

    private suspend fun seedPlayers(): List<Long> {
        val names = listOf(
            "Muhammad" to true,
            "Aina" to false,
            "Ben" to false,
            "Chandra" to false,
            "Deepa" to false,
            "Ezra" to false,
            "Farah" to false,
            "Gopal" to false,
        )
        val colours = listOf(
            "#2A78D6", "#EB6834", "#1BAF7A", "#EDA100",
            "#E87BA4", "#008300", "#4A3AA7", "#E34948",
        )
        return names.mapIndexed { index, (name, isSelf) ->
            val existing = playerDao.findByName(name)
            if (existing != null) {
                existing.id
            } else {
                val id = playerDao.insert(
                    PlayerEntity(name = name, isSelf = isSelf, colorHex = colours[index]),
                )
                if (isSelf) {
                    playerDao.clearSelfFlag()
                    playerDao.setSelfFlag(id)
                }
                id
            }
        }
    }

    private suspend fun seedGames(): List<Long> {
        val now = clock.nowMillis()
        val today = clock.today()

        val ids = mutableListOf<Long>()
        CATALOGUE.forEachIndexed { index, spec ->
            gameDao.getGameByTitle(spec.title)?.let {
                ids += it.id
                return@forEachIndexed
            }
            // Spread purchases across the last three years so "spend by year" has bars.
            val daysAgo = (index * 27L) % 1_000L
            val id = gameDao.insert(
                GameEntity(
                    title = spec.title,
                    yearPublished = spec.year,
                    minPlayers = spec.minPlayers,
                    maxPlayers = spec.maxPlayers,
                    bestPlayerCount = spec.best,
                    minPlaytimeMinutes = spec.minTime,
                    maxPlaytimeMinutes = spec.maxTime,
                    weight = spec.weight,
                    bggRating = 6.0 + (index % 25) / 10.0,
                    publisher = spec.publisher,
                    dateAdded = DateUtils.toIso(today.minusDays(daysAgo)),
                    price = spec.price,
                    currency = "MYR",
                    status = spec.status,
                    scoringMode = spec.scoring,
                    suddenDeathPossible = spec.suddenDeath,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            ids += id

            val tagIds = spec.mechanics.map { tagDao.upsertByName(it, TagKind.MECHANIC) } +
                spec.categories.map { tagDao.upsertByName(it, TagKind.CATEGORY) } +
                listOf(tagDao.upsertByName(spec.designer, TagKind.DESIGNER))
            tagDao.insertLinks(tagIds.map { GameTagCrossRef(gameId = id, tagId = it) })
        }

        // A couple of expansions, so the expansion and session-expansion paths have data.
        listOf("Catan" to "Catan: Seafarers", "Wingspan" to "Wingspan: Oceania").forEach { (base, expansion) ->
            val baseGame = gameDao.getGameByTitle(base) ?: return@forEach
            if (gameDao.getGameByTitle(expansion) != null) return@forEach
            ids += gameDao.insert(
                GameEntity(
                    title = expansion,
                    minPlayers = baseGame.minPlayers,
                    maxPlayers = baseGame.maxPlayers,
                    minPlaytimeMinutes = baseGame.minPlaytimeMinutes,
                    maxPlaytimeMinutes = baseGame.maxPlaytimeMinutes,
                    dateAdded = DateUtils.toIso(today.minusDays(200)),
                    price = 95.0,
                    isExpansion = true,
                    baseGameId = baseGame.id,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        return ids
    }

    /**
     * Two hundred plays over two years, weighted toward weekends and toward the games a
     * real collection actually plays rather than spread evenly.
     */
    private suspend fun seedSessions(gameIds: List<Long>, playerIds: List<Long>): Int {
        if (sessionDao.count() > 100) return 0

        val now = clock.nowMillis()
        val today = clock.today()
        val playable = gameIds.mapNotNull { gameDao.getGame(it) }
            .filter { !it.isExpansion && it.status.countsTowardCollection }
        if (playable.isEmpty()) return 0

        // A long tail: a handful of favourites carry most of the plays, which is what
        // makes the H-index and "most played" charts look like real data.
        val weights = playable.mapIndexed { index, game ->
            game to when {
                index < 5 -> 8
                index < 12 -> 4
                index < 25 -> 2
                else -> 1
            }
        }
        val pool = weights.flatMap { (game, weight) -> List(weight) { game } }

        var created = 0
        var date = today.minusDays(730)
        while (date < today && created < TARGET_SESSIONS) {
            val playsToday = when {
                date.dayOfWeek == DayOfWeek.SATURDAY -> random.nextInt(0, 4)
                date.dayOfWeek == DayOfWeek.SUNDAY -> random.nextInt(0, 3)
                date.dayOfWeek == DayOfWeek.FRIDAY -> random.nextInt(0, 2)
                else -> if (random.nextInt(100) < 12) 1 else 0
            }

            repeat(playsToday) {
                if (created >= TARGET_SESSIONS) return@repeat
                val game = pool.random(random)
                val minPlayers = (game.minPlayers ?: 2).coerceAtLeast(1)
                val maxPlayers = (game.maxPlayers ?: 4).coerceAtMost(playerIds.size)
                val headCount = random.nextInt(minPlayers, maxPlayers.coerceAtLeast(minPlayers) + 1)

                // The device owner is nearly always at the table; the others rotate.
                val seated = (listOf(playerIds.first()) + playerIds.drop(1).shuffled(random))
                    .take(headCount)

                val stated = ((game.minPlaytimeMinutes ?: 45) + (game.maxPlaytimeMinutes ?: 90)) / 2
                val teaching = random.nextInt(100) < 12
                val incomplete = random.nextInt(100) < 4
                val duration = when {
                    incomplete -> (stated * 0.35).toInt().coerceAtLeast(10)
                    // A teaching game genuinely runs long, which is the divergence the
                    // "actual versus BGG" card exists to show.
                    teaching -> (stated * 1.45).toInt()
                    else -> (stated * (0.85 + random.nextDouble() * 0.5)).toInt()
                }.coerceAtLeast(10)

                val cooperative = game.scoringMode == ScoringMode.COOPERATIVE
                val sessionId = sessionDao.insertSession(
                    SessionEntity(
                        gameId = game.id,
                        playedOn = DateUtils.toIso(date),
                        durationMinutes = duration,
                        playerCount = seated.size,
                        location = LOCATIONS.random(random),
                        isCooperative = cooperative,
                        coopOutcome = if (cooperative) {
                            if (random.nextInt(100) < 55) CoopOutcome.WIN else CoopOutcome.LOSS
                        } else {
                            null
                        },
                        isIncomplete = incomplete,
                        isTeachingGame = teaching,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )

                if (cooperative) {
                    val won = sessionDao.getSession(sessionId)?.coopOutcome == CoopOutcome.WIN
                    sessionDao.insertParticipants(
                        seated.map {
                            SessionPlayerEntity(
                                sessionId = sessionId,
                                playerId = it,
                                isWinner = won,
                            )
                        },
                    )
                } else {
                    val scores = seated.associateWith { random.nextInt(20, 120).toDouble() }
                    val ranked = scores.entries.sortedByDescending { it.value }
                    sessionDao.insertParticipants(
                        ranked.mapIndexed { index, entry ->
                            SessionPlayerEntity(
                                sessionId = sessionId,
                                playerId = entry.key,
                                score = entry.value,
                                placement = index + 1,
                                isWinner = index == 0,
                                faction = FACTIONS.random(random),
                            )
                        },
                    )
                }

                // Occasionally an expansion was on the table.
                if (random.nextInt(100) < 15) {
                    gameDao.getExpansionsOf(game.id).firstOrNull()?.let { expansion ->
                        sessionDao.insertExpansions(
                            listOf(SessionExpansionEntity(sessionId, expansion.id)),
                        )
                    }
                }
                created++
            }
            date = date.plusDays(1)
        }
        return created
    }

    private suspend fun seedRatings(gameIds: List<Long>): Int {
        if (rubricDao.countRatings() > 0) return 0
        val rubrics = rubricDao.getAllRubrics()
        if (rubrics.isEmpty()) return 0

        var rated = 0
        gameIds.take(TARGET_RATED_GAMES).forEach { gameId ->
            val game = gameDao.getGame(gameId) ?: return@forEach
            if (game.isExpansion) return@forEach
            val rubric = if (game.scoringMode == ScoringMode.COOPERATIVE) {
                rubrics.firstOrNull { it.name == "Cooperative" } ?: rubrics.first()
            } else {
                rubrics.first()
            }
            val criteria = rubricDao.getCriteria(rubric.id)
            if (criteria.isEmpty()) return@forEach

            val scores = criteria.associate { it.id to random.nextInt(4, 11).toDouble() }
            val computed = rubricRepository.computeScore(criteria, scores)
            val ratingId = rubricDao.insertRating(
                GameRatingEntity(
                    gameId = gameId,
                    rubricId = rubric.id,
                    ratedOn = DateUtils.toIso(clock.today().minusDays(random.nextInt(1, 400).toLong())),
                    computedScore = computed,
                ),
            )
            rubricDao.insertScores(
                scores.map { (criterionId, score) ->
                    GameRatingScoreEntity(
                        gameRatingId = ratingId,
                        criterionId = criterionId,
                        score = score,
                    )
                },
            )
            rated++
        }
        return rated
    }

    private data class GameSpec(
        val title: String,
        val year: Int,
        val minPlayers: Int,
        val maxPlayers: Int,
        val best: String,
        val minTime: Int,
        val maxTime: Int,
        val weight: Double,
        val price: Double,
        val designer: String,
        val publisher: String,
        val mechanics: List<String>,
        val categories: List<String>,
        val status: GameStatus = GameStatus.OWNED,
        val scoring: ScoringMode = ScoringMode.RANKED_SCORES,
        val suddenDeath: Boolean = false,
    )

    private companion object {
        const val TARGET_SESSIONS = 200
        const val TARGET_RATED_GAMES = 25

        val LOCATIONS = listOf(
            "Kitchen table", "Living room", "Board game cafe", "Ben's place", "Club night",
        )

        val FACTIONS = listOf(
            "Red", "Blue", "Green", "Yellow", "Marquise", "Eyrie", "Woodland", "Engineer",
        )

        val CATALOGUE = listOf(
            GameSpec("Catan", 1995, 3, 4, "4", 60, 120, 2.3, 150.0, "Klaus Teuber", "Kosmos",
                listOf("Trading", "Dice Rolling", "Network Building"), listOf("Economic", "Negotiation")),
            GameSpec("Wingspan", 2019, 1, 5, "3", 40, 70, 2.4, 220.0, "Elizabeth Hargrave", "Stonemaier",
                listOf("Engine Building", "Card Drafting", "Set Collection"), listOf("Animals", "Card Game")),
            GameSpec("Terraforming Mars", 2016, 1, 5, "3", 120, 180, 3.3, 260.0, "Jacob Fryxelius", "FryxGames",
                listOf("Engine Building", "Card Drafting", "Tile Placement"), listOf("Economic", "Science Fiction")),
            GameSpec("Brass: Birmingham", 2018, 2, 4, "3-4", 60, 120, 3.9, 300.0, "Martin Wallace", "Roxley",
                listOf("Network Building", "Hand Management"), listOf("Economic", "Industry")),
            GameSpec("Gloomhaven: Jaws of the Lion", 2020, 1, 4, "3", 30, 120, 3.6, 280.0, "Isaac Childres", "Cephalofair",
                listOf("Hand Management", "Campaign"), listOf("Adventure", "Fantasy"),
                scoring = ScoringMode.COOPERATIVE),
            GameSpec("Pandemic", 2008, 2, 4, "4", 45, 45, 2.4, 140.0, "Matt Leacock", "Z-Man",
                listOf("Hand Management", "Set Collection"), listOf("Medical"),
                scoring = ScoringMode.COOPERATIVE),
            GameSpec("7 Wonders", 2010, 3, 7, "5", 30, 30, 2.3, 170.0, "Antoine Bauza", "Repos",
                listOf("Card Drafting", "Set Collection"), listOf("Ancient", "Civilization")),
            GameSpec("Azul", 2017, 2, 4, "2", 30, 45, 1.8, 130.0, "Michael Kiesling", "Next Move",
                listOf("Tile Placement", "Set Collection"), listOf("Abstract")),
            GameSpec("Root", 2018, 2, 4, "4", 60, 90, 3.8, 250.0, "Cole Wehrle", "Leder Games",
                listOf("Area Majority", "Hand Management"), listOf("Fantasy", "Wargame")),
            GameSpec("Spirit Island", 2017, 1, 4, "2", 90, 120, 4.1, 290.0, "R. Eric Reuss", "Greater Than Games",
                listOf("Hand Management", "Area Majority"), listOf("Fantasy"),
                scoring = ScoringMode.COOPERATIVE),
            GameSpec("Ticket to Ride", 2004, 2, 5, "4", 30, 60, 1.8, 160.0, "Alan R. Moon", "Days of Wonder",
                listOf("Network Building", "Set Collection"), listOf("Trains")),
            GameSpec("Codenames", 2015, 2, 8, "6", 15, 15, 1.3, 70.0, "Vlaada Chvatil", "CGE",
                listOf("Team Play", "Deduction"), listOf("Party", "Word Game"),
                scoring = ScoringMode.NONE),
            GameSpec("The Crew", 2019, 2, 5, "4", 20, 20, 2.0, 60.0, "Thomas Sing", "KOSMOS",
                listOf("Trick-taking", "Team Play"), listOf("Card Game"),
                scoring = ScoringMode.COOPERATIVE),
            GameSpec("Everdell", 2018, 1, 4, "3", 40, 80, 2.8, 240.0, "James A. Wilson", "Starling",
                listOf("Worker Placement", "Card Drafting"), listOf("Animals", "Fantasy")),
            GameSpec("Scythe", 2016, 1, 5, "4", 90, 115, 3.4, 280.0, "Jamey Stegmaier", "Stonemaier",
                listOf("Area Majority", "Worker Placement"), listOf("Economic", "Science Fiction")),
            GameSpec("Splendor", 2014, 2, 4, "3", 30, 30, 1.8, 120.0, "Marc Andre", "Space Cowboys",
                listOf("Engine Building", "Set Collection"), listOf("Economic", "Renaissance")),
            GameSpec("Kingdomino", 2016, 2, 4, "4", 15, 20, 1.2, 80.0, "Bruno Cathala", "Blue Orange",
                listOf("Tile Placement", "Drafting"), listOf("Abstract")),
            GameSpec("Dune: Imperium", 2020, 1, 4, "4", 60, 120, 3.1, 270.0, "Paul Dennen", "Dire Wolf",
                listOf("Deck Building", "Worker Placement"), listOf("Science Fiction")),
            GameSpec("Ark Nova", 2021, 1, 4, "2", 90, 150, 3.7, 320.0, "Mathias Wigge", "Feuerland",
                listOf("Card Drafting", "Tile Placement"), listOf("Animals", "Economic")),
            GameSpec("Cascadia", 2021, 1, 4, "2", 30, 45, 1.8, 140.0, "Randy Flynn", "Flatout",
                listOf("Tile Placement", "Set Collection"), listOf("Animals", "Abstract")),
            GameSpec("Res Arcana", 2019, 2, 4, "2", 20, 60, 2.7, 150.0, "Tom Lehmann", "Sand Castle",
                listOf("Engine Building", "Hand Management"), listOf("Fantasy", "Card Game")),
            GameSpec("Concordia", 2013, 2, 5, "4", 90, 120, 3.0, 230.0, "Mac Gerdts", "PD-Verlag",
                listOf("Deck Building", "Network Building"), listOf("Ancient", "Economic")),
            GameSpec("Great Western Trail", 2016, 2, 4, "3", 75, 150, 3.7, 250.0, "Alexander Pfister", "eggertspiele",
                listOf("Deck Building", "Worker Placement"), listOf("American West", "Economic")),
            GameSpec("Just One", 2018, 3, 7, "5", 20, 20, 1.1, 60.0, "Ludovic Roudy", "Repos",
                listOf("Team Play", "Deduction"), listOf("Party", "Word Game"),
                scoring = ScoringMode.COOPERATIVE),
            GameSpec("The Mind", 2018, 2, 4, "4", 15, 15, 1.1, 40.0, "Wolfgang Warsch", "NSV",
                listOf("Team Play"), listOf("Card Game", "Party"),
                scoring = ScoringMode.COOPERATIVE),
            GameSpec("Sushi Go Party", 2016, 2, 8, "5", 20, 20, 1.4, 90.0, "Phil Walker-Harding", "Gamewright",
                listOf("Card Drafting", "Set Collection"), listOf("Card Game", "Party")),
            GameSpec("Love Letter", 2012, 2, 4, "4", 20, 20, 1.2, 35.0, "Seiji Kanai", "AEG",
                listOf("Deduction", "Hand Management"), listOf("Card Game", "Bluffing")),
            GameSpec("Skull", 2011, 3, 6, "6", 15, 45, 1.4, 75.0, "Herve Marly", "Lui-meme",
                listOf("Bluffing", "Betting"), listOf("Card Game", "Party")),
            GameSpec("The Resistance: Avalon", 2012, 5, 10, "7", 30, 30, 1.8, 80.0, "Don Eskridge", "Indie Boards",
                listOf("Deduction", "Team Play"), listOf("Bluffing", "Party"),
                scoring = ScoringMode.NONE),
            GameSpec("Secret Hitler", 2016, 5, 10, "7", 45, 45, 1.7, 130.0, "Max Temkin", "Goat Wolf",
                listOf("Deduction", "Voting"), listOf("Bluffing", "Party"),
                scoring = ScoringMode.NONE),
            GameSpec("Patchwork", 2014, 2, 2, "2", 15, 30, 1.6, 100.0, "Uwe Rosenberg", "Lookout",
                listOf("Tile Placement"), listOf("Abstract", "Puzzle")),
            GameSpec("Jaipur", 2009, 2, 2, "2", 30, 30, 1.5, 85.0, "Sebastien Pauchon", "Space Cowboys",
                listOf("Set Collection", "Hand Management"), listOf("Card Game", "Economic")),
            GameSpec("7 Wonders Duel", 2015, 2, 2, "2", 30, 30, 2.2, 130.0, "Antoine Bauza", "Repos",
                listOf("Card Drafting", "Set Collection"), listOf("Ancient", "Civilization"),
                suddenDeath = true),
            GameSpec("Lost Ruins of Arnak", 2020, 1, 4, "2", 30, 120, 2.9, 260.0, "Elwen", "CGE",
                listOf("Deck Building", "Worker Placement"), listOf("Adventure", "Exploration")),
            GameSpec("Viticulture Essential", 2015, 1, 6, "4", 45, 90, 2.9, 240.0, "Jamey Stegmaier", "Stonemaier",
                listOf("Worker Placement", "Hand Management"), listOf("Economic", "Farming")),
            GameSpec("Clank!", 2016, 2, 4, "4", 30, 60, 2.2, 200.0, "Paul Dennen", "Dire Wolf",
                listOf("Deck Building", "Push Your Luck"), listOf("Adventure", "Fantasy")),
            GameSpec("Photosynthesis", 2017, 2, 4, "3", 30, 60, 2.3, 160.0, "Hjalmar Hach", "Blue Orange",
                listOf("Area Majority", "Tile Placement"), listOf("Abstract", "Environmental")),
            GameSpec("Quacks of Quedlinburg", 2018, 2, 4, "4", 45, 45, 1.9, 180.0, "Wolfgang Warsch", "Schmidt",
                listOf("Push Your Luck", "Bag Building"), listOf("Fantasy")),
            GameSpec("Heat: Pedal to the Metal", 2022, 1, 6, "4", 30, 60, 2.3, 230.0, "Asger Sams Granerud", "Days of Wonder",
                listOf("Hand Management", "Racing"), listOf("Racing", "Sports")),
            GameSpec("Frosthaven", 2023, 1, 4, "3", 60, 120, 4.2, 600.0, "Isaac Childres", "Cephalofair",
                listOf("Hand Management", "Campaign"), listOf("Adventure", "Fantasy"),
                scoring = ScoringMode.COOPERATIVE),
            GameSpec("Nucleum", 2023, 1, 4, "3", 60, 150, 4.0, 310.0, "Simone Luciani", "Board&Dice",
                listOf("Network Building", "Tile Placement"), listOf("Economic", "Industry"),
                status = GameStatus.WISHLIST),
            GameSpec("Sky Team", 2023, 2, 2, "2", 15, 20, 2.0, 120.0, "Luc Remond", "Le Scorpion Masque",
                listOf("Dice Placement", "Team Play"), listOf("Aviation"),
                scoring = ScoringMode.COOPERATIVE, status = GameStatus.PREORDERED),
        )
    }
}
