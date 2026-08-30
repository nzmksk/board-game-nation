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
 * Mechanics, categories and designers are the same table, split by kind, so BGG's
 * open-ended lists never force a schema migration.
 */
enum class TagKind {
    MECHANIC, CATEGORY, DESIGNER, CUSTOM;

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
 * How a play ended, when that differs from playing through to final scoring.
 *
 * Some games can end the moment a condition is met -- 7 Wonders Duel's military and
 * scientific supremacy both stop the game before anyone counts a victory point. The
 * ending is a property of one play rather than of the game, which is why this sits on
 * the session and not beside [ScoringMode].
 *
 * A null column means the ordinary case: the table played to the end and scored it.
 */
enum class SessionEndCondition {
    SUDDEN_DEATH;

    companion object {
        fun fromStorage(value: String?): SessionEndCondition? =
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

    /**
     * Players are split into sides and a side wins together. Hidden-role games work
     * this way: in Secret Hitler the liberals win or the fascists do, and which side
     * somebody was on is not the same as the role they were dealt.
     */
    TEAM_BASED,

    /** Neither scores nor order matter; just record who played. */
    NONE;

    companion object {
        fun fromStorage(value: String?): ScoringMode =
            entries.firstOrNull { it.name == value } ?: RANKED_SCORES
    }
}

/**
 * What the timer is counting.
 *
 * The dual countdown is a competitive tool: it only means something when players take
 * turns worth policing. A co-op, a filler or a party game has one number worth
 * recording -- how long the whole thing took -- so [COUNT_UP] times the table rather
 * than the seats.
 */
enum class TimerMode {
    /** Per-seat turn and bank clocks counting down. The original behaviour. */
    TURN_BASED,

    /** One clock for the table, counting up until somebody stops it. */
    COUNT_UP;

    companion object {
        fun fromStorage(value: String?): TimerMode =
            entries.firstOrNull { it.name == value } ?: TURN_BASED
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
