package com.boardgamenation.tracker.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.boardgamenation.tracker.core.time.FixedClock
import com.boardgamenation.tracker.data.db.entity.GameEntity
import com.boardgamenation.tracker.data.db.entity.PlayerEntity
import com.boardgamenation.tracker.data.db.entity.SessionEntity
import com.boardgamenation.tracker.data.db.entity.SessionPlayerEntity
import com.boardgamenation.tracker.domain.model.GameStatus
import java.time.ZoneId

/**
 * A real Room database, in memory.
 *
 * The DAO tests run against the actual schema and the actual SQL rather than a mock, so
 * a query that compiles but returns the wrong rows still fails here. Room's compile-time
 * verification catches syntax; only executing the statements catches semantics.
 */
object DatabaseTestFixture {

    /** 2026-03-15T12:00:00Z, so date arithmetic in tests is predictable. */
    const val NOW = 1_773_576_000_000L

    val clock = FixedClock(NOW, ZoneId.of("UTC"))

    fun database(): AppDatabase {
        val context: Context = ApplicationProvider.getApplicationContext()
        return Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    fun game(
        title: String,
        id: Long = 0,
        bggId: Long? = null,
        status: GameStatus = GameStatus.OWNED,
        price: Double? = null,
        minPlayers: Int? = 2,
        maxPlayers: Int? = 4,
        minPlaytime: Int? = 45,
        maxPlaytime: Int? = 90,
        weight: Double? = 2.5,
        isExpansion: Boolean = false,
        baseGameId: Long? = null,
        dateAdded: String = "2026-01-01"
    ) = GameEntity(
        id = id,
        bggId = bggId,
        title = title,
        minPlayers = minPlayers,
        maxPlayers = maxPlayers,
        minPlaytimeMinutes = minPlaytime,
        maxPlaytimeMinutes = maxPlaytime,
        weight = weight,
        dateAdded = dateAdded,
        price = price,
        status = status,
        isExpansion = isExpansion,
        baseGameId = baseGameId,
        createdAt = NOW,
        updatedAt = NOW
    )

    fun player(name: String, id: Long = 0, isSelf: Boolean = false) = PlayerEntity(id = id, name = name, isSelf = isSelf)

    fun session(
        gameId: Long,
        playedOn: String,
        id: Long = 0,
        durationMinutes: Int = 60,
        playerCount: Int = 2,
        isIncomplete: Boolean = false,
        isTeaching: Boolean = false,
        isDraft: Boolean = false,
        isCooperative: Boolean = false
    ) = SessionEntity(
        id = id,
        gameId = gameId,
        playedOn = playedOn,
        durationMinutes = durationMinutes,
        playerCount = playerCount,
        isIncomplete = isIncomplete,
        isTeachingGame = isTeaching,
        isDraft = isDraft,
        isCooperative = isCooperative,
        createdAt = NOW,
        updatedAt = NOW
    )

    fun participant(
        sessionId: Long,
        playerId: Long,
        score: Double? = null,
        isWinner: Boolean = false,
        placement: Int? = null,
        turnOrder: Int? = null,
        seat: Int? = null
    ) = SessionPlayerEntity(
        sessionId = sessionId,
        playerId = playerId,
        score = score,
        isWinner = isWinner,
        placement = placement,
        turnOrder = turnOrder,
        seat = seat
    )
}
