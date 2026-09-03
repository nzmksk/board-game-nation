package com.boardgamenation.tracker.data.db

import app.cash.turbine.test
import com.boardgamenation.tracker.data.db.dao.GameDao
import com.boardgamenation.tracker.data.db.dao.SessionDao
import com.boardgamenation.tracker.data.db.dao.TagDao
import com.boardgamenation.tracker.data.db.entity.GameTagCrossRef
import com.boardgamenation.tracker.data.db.entity.TagEntity
import com.boardgamenation.tracker.data.db.query.GameQueryBuilder
import com.boardgamenation.tracker.domain.model.CollectionFilter
import com.boardgamenation.tracker.domain.model.CollectionSort
import com.boardgamenation.tracker.domain.model.GameStatus
import com.boardgamenation.tracker.domain.model.TagKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GameDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var gameDao: GameDao
    private lateinit var tagDao: TagDao
    private lateinit var sessionDao: SessionDao

    @Before
    fun setUp() {
        db = DatabaseTestFixture.database()
        gameDao = db.gameDao()
        tagDao = db.tagDao()
        sessionDao = db.sessionDao()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun collection(filter: CollectionFilter) = gameDao.observeCollection(GameQueryBuilder.build(filter)).first()

    @Test
    fun `a game round trips`() = runTest {
        val id = gameDao.insert(DatabaseTestFixture.game("Catan", bggId = 13))
        val loaded = gameDao.getGame(id)
        assertEquals("Catan", loaded?.title)
        assertEquals(13L, loaded?.bggId)
    }

    /**
     * SQLite treats NULLs in a unique index as distinct, which is exactly the "unique
     * where not null" behaviour the schema wants: many hand-entered games, no bgg_id.
     */
    @Test
    fun `several games may have no bgg id`() = runTest {
        gameDao.insert(DatabaseTestFixture.game("Prototype A"))
        gameDao.insert(DatabaseTestFixture.game("Prototype B"))
        assertEquals(2, gameDao.count())
    }

    @Test
    fun `search matches part of a title`() = runTest {
        gameDao.insert(DatabaseTestFixture.game("Terraforming Mars"))
        gameDao.insert(DatabaseTestFixture.game("Catan"))

        val results = collection(CollectionFilter(search = "mars"))
        assertEquals(1, results.size)
        assertEquals("Terraforming Mars", results.first().title)
    }

    /** A literal percent in the search box must not match everything. */
    @Test
    fun `a wildcard character in the search is treated literally`() = runTest {
        gameDao.insert(DatabaseTestFixture.game("100% Wool"))
        gameDao.insert(DatabaseTestFixture.game("Catan"))

        assertEquals(1, collection(CollectionFilter(search = "100%")).size)
        assertEquals(0, collection(CollectionFilter(search = "%%%")).size)
    }

    @Test
    fun `the player count filter respects the game's range`() = runTest {
        gameDao.insert(DatabaseTestFixture.game("Two only", minPlayers = 2, maxPlayers = 2))
        gameDao.insert(DatabaseTestFixture.game("Party", minPlayers = 4, maxPlayers = 10))

        assertEquals(listOf("Two only"), collection(CollectionFilter(playerCount = 2)).map { it.title })
        assertEquals(listOf("Party"), collection(CollectionFilter(playerCount = 6)).map { it.title })
    }

    @Test
    fun `the status filter narrows the list`() = runTest {
        gameDao.insert(DatabaseTestFixture.game("Owned one"))
        gameDao.insert(DatabaseTestFixture.game("Wanted", status = GameStatus.WISHLIST))

        val owned = collection(CollectionFilter(statuses = setOf(GameStatus.OWNED)))
        assertEquals(listOf("Owned one"), owned.map { it.title })
    }

    @Test
    fun `play count and cost per play are computed in the query`() = runTest {
        val gameId = gameDao.insert(DatabaseTestFixture.game("Catan", price = 120.0))
        repeat(4) { index ->
            sessionDao.insertSession(
                DatabaseTestFixture.session(gameId, playedOn = "2026-02-0${index + 1}")
            )
        }

        val row = collection(CollectionFilter()).first()
        assertEquals(4, row.playCount)
        assertEquals(30.0, row.costPerPlay!!, 0.001)
        assertEquals("2026-02-04", row.lastPlayed)
    }

    /** An unfinished timer session is not a play and must not inflate any count. */
    @Test
    fun `drafts are excluded from play counts`() = runTest {
        val gameId = gameDao.insert(DatabaseTestFixture.game("Catan"))
        sessionDao.insertSession(DatabaseTestFixture.session(gameId, "2026-02-01"))
        sessionDao.insertSession(DatabaseTestFixture.session(gameId, "2026-02-02", isDraft = true))

        assertEquals(1, collection(CollectionFilter()).first().playCount)
    }

    @Test
    fun `cost per play is null for an unplayed game`() = runTest {
        gameDao.insert(DatabaseTestFixture.game("Shelf ornament", price = 200.0))
        assertNull(collection(CollectionFilter()).first().costPerPlay)
    }

    @Test
    fun `sorting by play count puts the most played first`() = runTest {
        val a = gameDao.insert(DatabaseTestFixture.game("Played once"))
        val b = gameDao.insert(DatabaseTestFixture.game("Played thrice"))
        sessionDao.insertSession(DatabaseTestFixture.session(a, "2026-02-01"))
        repeat(3) { index ->
            sessionDao.insertSession(DatabaseTestFixture.session(b, "2026-02-1$index"))
        }

        val sorted = collection(
            CollectionFilter(sort = CollectionSort.PLAY_COUNT, ascending = false)
        )
        assertEquals("Played thrice", sorted.first().title)
    }

    @Test
    fun `games with no price sort last by price, not first`() = runTest {
        gameDao.insert(DatabaseTestFixture.game("Unpriced", price = null))
        gameDao.insert(DatabaseTestFixture.game("Cheap", price = 10.0))

        val ascending = collection(
            CollectionFilter(sort = CollectionSort.PRICE, ascending = true)
        )
        assertEquals("Cheap", ascending.first().title)
        assertEquals("Unpriced", ascending.last().title)
    }

    @Test
    fun `tag filters match games carrying any of the chosen tags`() = runTest {
        val gameId = gameDao.insert(DatabaseTestFixture.game("Engine builder"))
        gameDao.insert(DatabaseTestFixture.game("Party game"))
        val tagId = tagDao.insert(TagEntity(name = "Engine Building", kind = TagKind.MECHANIC))
        tagDao.insertLinks(listOf(GameTagCrossRef(gameId, tagId)))

        val filtered = collection(CollectionFilter(tagIds = setOf(tagId)))
        assertEquals(listOf("Engine builder"), filtered.map { it.title })
    }

    @Test
    fun `aggregates exclude incomplete games from the average but count the play`() = runTest {
        val gameId = gameDao.insert(DatabaseTestFixture.game("Catan"))
        sessionDao.insertSession(
            DatabaseTestFixture.session(gameId, "2026-02-01", durationMinutes = 60)
        )
        sessionDao.insertSession(
            DatabaseTestFixture.session(gameId, "2026-02-02", durationMinutes = 10, isIncomplete = true)
        )

        val aggregates = gameDao.observeAggregates(gameId).first()
        assertEquals(2, aggregates.playCount)
        assertEquals(70, aggregates.totalMinutes)
        // The abandoned ten-minute session says nothing about how long the game takes.
        assertEquals(60.0, aggregates.avgMinutes!!, 0.001)
    }

    @Test
    fun `teaching games are separated out of the duration average`() = runTest {
        val gameId = gameDao.insert(DatabaseTestFixture.game("Catan"))
        sessionDao.insertSession(
            DatabaseTestFixture.session(gameId, "2026-02-01", durationMinutes = 60)
        )
        sessionDao.insertSession(
            DatabaseTestFixture.session(gameId, "2026-02-02", durationMinutes = 120, isTeaching = true)
        )

        val aggregates = gameDao.observeAggregates(gameId).first()
        assertEquals(90.0, aggregates.avgMinutes!!, 0.001)
        assertEquals(60.0, aggregates.avgMinutesNonTeaching!!, 0.001)
    }

    @Test
    fun `deleting a game cascades its sessions rather than orphaning them`() = runTest {
        val gameId = gameDao.insert(DatabaseTestFixture.game("Catan"))
        sessionDao.insertSession(DatabaseTestFixture.session(gameId, "2026-02-01"))
        assertEquals(1, sessionDao.count())

        gameDao.delete(gameDao.getGame(gameId)!!)
        assertEquals(0, sessionDao.count())
    }

    /** An expansion outlives its base game; the collection keeps the box either way. */
    @Test
    fun `deleting a base game detaches its expansions`() = runTest {
        val baseId = gameDao.insert(DatabaseTestFixture.game("Catan"))
        val expansionId = gameDao.insert(
            DatabaseTestFixture.game("Seafarers", isExpansion = true, baseGameId = baseId)
        )

        gameDao.delete(gameDao.getGame(baseId)!!)
        val expansion = gameDao.getGame(expansionId)
        assertNotNull(expansion)
        assertNull(expansion?.baseGameId)
    }

    @Test
    fun `replacing tags swaps the whole set`() = runTest {
        val gameId = gameDao.insert(DatabaseTestFixture.game("Catan"))
        val first = tagDao.upsertByName("Trading", TagKind.MECHANIC)
        val second = tagDao.upsertByName("Dice Rolling", TagKind.MECHANIC)

        gameDao.replaceTags(gameId, listOf(first))
        assertEquals(listOf("Trading"), tagDao.getForGame(gameId).map { it.name })

        gameDao.replaceTags(gameId, listOf(second))
        assertEquals(listOf("Dice Rolling"), tagDao.getForGame(gameId).map { it.name })
    }

    @Test
    fun `upserting a tag by name never duplicates it`() = runTest {
        val first = tagDao.upsertByName("Deck Building", TagKind.MECHANIC)
        val second = tagDao.upsertByName("Deck Building", TagKind.MECHANIC)
        assertEquals(first, second)
        assertEquals(1, tagDao.count())
    }

    /**
     * Designers were promoted out of a comma-joined column into this same table, which is
     * what makes "everything by Uwe Rosenberg" a filter rather than a text search. The
     * filter is deliberately kind-agnostic, so this needs no new SQL -- but it does need
     * proving, because the whole point of the move was to gain this.
     */
    @Test
    fun `a designer tag filters the collection like any other tag`() = runTest {
        val rosenberg = gameDao.insert(DatabaseTestFixture.game("Agricola"))
        gameDao.insert(DatabaseTestFixture.game("Azul"))
        val tagId = tagDao.upsertByName("Uwe Rosenberg", TagKind.DESIGNER)
        tagDao.insertLinks(listOf(GameTagCrossRef(rosenberg, tagId)))

        assertEquals(
            listOf("Agricola"),
            collection(CollectionFilter(tagIds = setOf(tagId))).map { it.title }
        )
    }

    @Test
    fun `a designer left with no games is pruned like any other orphan tag`() = runTest {
        val gameId = gameDao.insert(DatabaseTestFixture.game("Agricola"))
        val designer = tagDao.upsertByName("Uwe Rosenberg", TagKind.DESIGNER)
        val shared = tagDao.upsertByName("Reiner Knizia", TagKind.DESIGNER)
        val other = gameDao.insert(DatabaseTestFixture.game("Ra"))
        tagDao.insertLinks(
            listOf(
                GameTagCrossRef(gameId, designer),
                GameTagCrossRef(gameId, shared),
                GameTagCrossRef(other, shared)
            )
        )

        gameDao.delete(gameDao.getGame(gameId)!!)
        tagDao.pruneOrphans()

        val remaining = tagDao.getAll().map { it.name }
        assertEquals(listOf("Reiner Knizia"), remaining)
    }

    @Test
    fun `the same name under a different kind is a different tag`() = runTest {
        tagDao.upsertByName("Economic", TagKind.MECHANIC)
        tagDao.upsertByName("Economic", TagKind.CATEGORY)
        assertEquals(2, tagDao.count())
    }

    @Test
    fun `lending marks a game out of possession and back again`() = runTest {
        val gameId = gameDao.insert(DatabaseTestFixture.game("Catan"))
        gameDao.markLent(gameId, "Ben", "2026-01-01", DatabaseTestFixture.NOW)

        val lent = gameDao.getGame(gameId)!!
        assertTrue(!lent.inPossession)
        assertEquals("Ben", lent.lentTo)
        assertEquals(GameStatus.LENT_OUT, lent.status)

        gameDao.markReturned(gameId, DatabaseTestFixture.NOW)
        val returned = gameDao.getGame(gameId)!!
        assertTrue(returned.inPossession)
        assertNull(returned.lentTo)
    }

    @Test
    fun `loans older than the cutoff are found by string comparison`() = runTest {
        val old = gameDao.insert(DatabaseTestFixture.game("Long gone"))
        val recent = gameDao.insert(DatabaseTestFixture.game("Just lent"))
        gameDao.markLent(old, "Ben", "2025-12-01", DatabaseTestFixture.NOW)
        gameDao.markLent(recent, "Aina", "2026-03-14", DatabaseTestFixture.NOW)

        val overdue = gameDao.getLoansOlderThan("2026-02-13")
        assertEquals(listOf("Long gone"), overdue.map { it.title })
    }

    @Test
    fun `the collection flow re-emits when a game is added`() = runTest {
        gameDao.observeCollection(GameQueryBuilder.build(CollectionFilter())).test {
            assertEquals(0, awaitItem().size)
            gameDao.insert(DatabaseTestFixture.game("Catan"))
            assertEquals(1, awaitItem().size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Logging a play must move the play count on a list that is already on screen. */
    @Test
    fun `the collection flow re-emits when a session changes the play count`() = runTest {
        val gameId = gameDao.insert(DatabaseTestFixture.game("Catan"))
        gameDao.observeCollection(GameQueryBuilder.build(CollectionFilter())).test {
            assertEquals(0, awaitItem().first().playCount)
            sessionDao.insertSession(DatabaseTestFixture.session(gameId, "2026-02-01"))
            assertEquals(1, awaitItem().first().playCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- faction win rates ----------------------------------------------------------

    /**
     * Seats [factions] on a play of [gameId], marking the winners named in [winners].
     */
    private suspend fun play(
        gameId: Long,
        playedOn: String,
        factions: Map<Long, String>,
        winners: Set<Long>,
        incomplete: Boolean = false,
        draft: Boolean = false
    ) {
        val sessionId = sessionDao.insertSession(
            DatabaseTestFixture.session(
                gameId = gameId,
                playedOn = playedOn,
                playerCount = factions.size,
                isIncomplete = incomplete,
                isDraft = draft
            )
        )
        sessionDao.insertParticipants(
            factions.map { (playerId, faction) ->
                DatabaseTestFixture.participant(
                    sessionId = sessionId,
                    playerId = playerId,
                    isWinner = playerId in winners
                ).copy(faction = faction)
            }
        )
    }

    private suspend fun seedPlayers(vararg names: String): List<Long> = names.map { db.playerDao().insert(DatabaseTestFixture.player(it)) }

    @Test
    fun `win rate per faction counts every player who used it`() = runTest {
        val gameId = gameDao.insert(DatabaseTestFixture.game("7 Wonders"))
        val (aina, ben) = seedPlayers("Aina", "Ben")

        play(gameId, "2026-01-05", mapOf(aina to "Halikarnassos", ben to "Alexandria"), setOf(aina))
        play(gameId, "2026-01-12", mapOf(aina to "Alexandria", ben to "Halikarnassos"), setOf(aina))
        play(gameId, "2026-01-19", mapOf(aina to "Halikarnassos", ben to "Alexandria"), setOf(ben))

        val records = gameDao.observeFactionRecords(gameId).first().associateBy { it.faction }

        assertEquals(3, records.getValue("Halikarnassos").plays)
        assertEquals(1, records.getValue("Halikarnassos").wins)
        assertEquals(33, records.getValue("Halikarnassos").winPercent)
        assertEquals(3, records.getValue("Alexandria").plays)
        assertEquals(2, records.getValue("Alexandria").wins)
        assertEquals(66, records.getValue("Alexandria").winPercent)
    }

    @Test
    fun `factions come back best win rate first`() = runTest {
        val gameId = gameDao.insert(DatabaseTestFixture.game("7 Wonders"))
        val (aina, ben) = seedPlayers("Aina", "Ben")

        play(gameId, "2026-01-05", mapOf(aina to "Rhodos", ben to "Ephesos"), setOf(aina))
        play(gameId, "2026-01-12", mapOf(aina to "Rhodos", ben to "Ephesos"), setOf(aina))

        assertEquals(
            listOf("Rhodos", "Ephesos"),
            gameDao.observeFactionRecords(gameId).first().map { it.faction }
        )
    }

    /** An abandoned game has no winner; counting it would look like everyone lost. */
    @Test
    fun `abandoned and draft plays stay out of the win rate`() = runTest {
        val gameId = gameDao.insert(DatabaseTestFixture.game("7 Wonders"))
        val (aina, ben) = seedPlayers("Aina", "Ben")

        play(gameId, "2026-01-05", mapOf(aina to "Babylon", ben to "Olympia"), setOf(aina))
        play(gameId, "2026-01-12", mapOf(aina to "Babylon"), emptySet(), incomplete = true)
        play(gameId, "2026-01-19", mapOf(aina to "Babylon"), emptySet(), draft = true)

        val babylon = gameDao.observeFactionRecords(gameId).first().first { it.faction == "Babylon" }
        assertEquals(1, babylon.plays)
        assertEquals(100, babylon.winPercent)
    }

    @Test
    fun `one faction spelled two ways is one faction`() = runTest {
        val gameId = gameDao.insert(DatabaseTestFixture.game("7 Wonders"))
        val (aina, ben) = seedPlayers("Aina", "Ben")

        play(gameId, "2026-01-05", mapOf(aina to "Gizah", ben to "Ephesos"), setOf(aina))
        play(gameId, "2026-01-12", mapOf(aina to "gizah", ben to "Ephesos"), setOf(ben))

        val records = gameDao.observeFactionRecords(gameId).first()
        assertEquals(2, records.size)
        assertEquals(2, records.first { it.faction.equals("Gizah", ignoreCase = true) }.plays)
    }

    @Test
    fun `players who recorded no faction are simply absent`() = runTest {
        val gameId = gameDao.insert(DatabaseTestFixture.game("Catan"))
        val (aina, ben) = seedPlayers("Aina", "Ben")

        play(gameId, "2026-01-05", mapOf(aina to "   ", ben to "Ephesos"), setOf(aina))

        assertEquals(
            listOf("Ephesos"),
            gameDao.observeFactionRecords(gameId).first().map { it.faction }
        )
    }

    @Test
    fun `another game's factions do not leak in`() = runTest {
        val wonders = gameDao.insert(DatabaseTestFixture.game("7 Wonders"))
        val root = gameDao.insert(DatabaseTestFixture.game("Root"))
        val (aina, ben) = seedPlayers("Aina", "Ben")

        play(wonders, "2026-01-05", mapOf(aina to "Rhodos", ben to "Ephesos"), setOf(aina))
        play(root, "2026-01-06", mapOf(aina to "Marquise", ben to "Eyrie"), setOf(ben))

        assertEquals(
            listOf("Marquise", "Eyrie").sorted(),
            gameDao.observeFactionRecords(root).first().map { it.faction }.sorted()
        )
    }
}
