package com.boardgamenation.tracker.domain.model

/** Where a game sits in the collection lifecycle. Stored as TEXT. */
enum class GameStatus {
    OWNED, WISHLIST, PREORDERED, SOLD, LENT_OUT;

    /** Statuses that count toward collection totals and monetary value. */
    val countsTowardCollection: Boolean
        get() = this == OWNED || this == LENT_OUT

    companion object {
        fun fromStorage(value: String?): GameStatus =
            entries.firstOrNull { it.name == value } ?: OWNED
    }
}

/**
 * Mechanics and categories are the same table, split by kind, so BGG's open-ended
 * mechanic lists never force a schema migration.
 */
enum class TagKind {
    MECHANIC, CATEGORY, CUSTOM;

    companion object {
        fun fromStorage(value: String?): TagKind =
            entries.firstOrNull { it.name == value } ?: CUSTOM
    }
}

/** Outcome of a cooperative game, where the table wins or loses as one. */
enum class CoopOutcome {
    WIN, LOSS, NA;

    companion object {
        fun fromStorage(value: String?): CoopOutcome? =
            value?.let { v -> entries.firstOrNull { it.name == v } }
    }
}

/**
 * How results are entered for a game. Remembered per game so the logging form opens
 * in the right shape without being re-picked every session.
 */
enum class ScoringMode {
    /** Numeric scores entered; placements derive from them. */
    RANKED_SCORES,

    /** No numbers, the user orders players directly. */
    MANUAL_PLACEMENT,

    /** One win/loss for the whole table. */
    COOPERATIVE,

    /** Neither scores nor order matter; just record who played. */
    NONE;

    companion object {
        fun fromStorage(value: String?): ScoringMode =
            entries.firstOrNull { it.name == value } ?: RANKED_SCORES
    }
}

/** What happens when a player's bank timer runs out. */
enum class BankExhaustedBehaviour {
    /** Flag the player and count into negative overtime for the record. Default. */
    FLAG_AND_OVERTIME,

    /** Pass the turn automatically. */
    AUTO_PASS;

    companion object {
        fun fromStorage(value: String?): BankExhaustedBehaviour =
            entries.firstOrNull { it.name == value } ?: FLAG_AND_OVERTIME
    }
}

/** Lifecycle of the turn timer. Persisted so a process kill is recoverable. */
enum class TimerRunState {
    IDLE, RUNNING, PAUSED, STOPPED;

    companion object {
        fun fromStorage(value: String?): TimerRunState =
            entries.firstOrNull { it.name == value } ?: IDLE
    }
}

/** Which clock the active player is currently spending. */
enum class ActiveClock {
    TURN, BANK, OVERTIME;

    companion object {
        fun fromStorage(value: String?): ActiveClock =
            entries.firstOrNull { it.name == value } ?: TURN
    }
}

/** How a CSV import reconciles with existing data. */
enum class ImportMode { MERGE, REPLACE }

/** Theme preference. */
enum class ThemeMode { LIGHT, DARK, SYSTEM;
    companion object {
        fun fromStorage(value: String?): ThemeMode =
            entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}
