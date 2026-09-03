package com.boardgamenation.tracker.domain.model

/** Where a game sits in the collection lifecycle. Stored as TEXT. */
enum class GameStatus {
    OWNED,
    WISHLIST,
    PREORDERED,
    SOLD,
    LENT_OUT;

    /** Statuses that count toward collection totals and monetary value. */
    val countsTowardCollection: Boolean
        get() = this == OWNED || this == LENT_OUT

    companion object {
        fun fromStorage(value: String?): GameStatus = entries.firstOrNull { it.name == value } ?: OWNED
    }
}

/**
 * Mechanics, categories and designers are the same table, split by kind, so BGG's
 * open-ended lists never force a schema migration.
 */
enum class TagKind {
    MECHANIC,
    CATEGORY,
    DESIGNER,
    CUSTOM;

    companion object {
        fun fromStorage(value: String?): TagKind = entries.firstOrNull { it.name == value } ?: CUSTOM
    }
}

/** Outcome of a cooperative game, where the table wins or loses as one. */
enum class CoopOutcome {
    WIN,
    LOSS,
    NA;

    companion object {
        fun fromStorage(value: String?): CoopOutcome? = value?.let { v -> entries.firstOrNull { it.name == v } }
    }
}

/**
 * How a play ended.
 *
 * Every play ends somehow, so this is asked of all of them whatever the scoring. The
 * ending belongs to the play rather than to the game -- the same game runs to its own
 * last round one night and is stopped by a rule the next -- which is why it sits on the
 * session and not beside [ScoringMode].
 *
 * A null column is a play that reached the app without being asked: one logged before
 * the column existed, or restored from an archive exported then. It means nobody said,
 * not that nothing happened.
 */
enum class SessionEndCondition {
    /** Played through to the game's own ending: the last round, the points target. */
    STANDARD,

    /**
     * A rule stopped it before that ending was reached -- 7 Wonders Duel's military and
     * scientific supremacy, Pandemic's uncontrolled outbreak, Hitler elected Chancellor.
     * `end_reason` names which one in the user's own words, because every game words it
     * differently and no fixed list would survive the next game bought.
     */
    SPECIFIC,

    /** The table gave up. The only ending with no result to record. */
    ABANDONED;

    companion object {

        /**
         * "SUDDEN_DEATH" is what this column held while an early ending was the only one
         * the app could record, and archives exported then still say it. It described
         * exactly [SPECIFIC]: a rule stopping the game before its own ending.
         */
        fun fromStorage(value: String?): SessionEndCondition? = when (value) {
            null -> null
            "SUDDEN_DEATH" -> SPECIFIC
            else -> entries.firstOrNull { it.name == value }
        }
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

    /**
     * Whether a play in this mode has a number against each player's name.
     *
     * Only ranked scoring does, and the logging form shows the score field for that
     * mode alone. That makes this the line between a score somebody meant to record and
     * one left behind by a mode the play used to be in: a game switched to placements,
     * sides or nothing at all keeps no scores, because there is nowhere left in the app
     * to see or correct them.
     */
    val recordsScores: Boolean get() = this == RANKED_SCORES

    /**
     * Whether a play in this mode puts each player on a side.
     *
     * Carries more weight than [recordsScores] does. A session never stores the mode it
     * was played under, and one of the things it is worked out from on the way back is
     * whether any row has a side on it -- so a side left behind by a mode change does
     * not merely sit there unread, it pins the play to team scoring with no way out.
     */
    val recordsSides: Boolean get() = this == TEAM_BASED

    companion object {
        fun fromStorage(value: String?): ScoringMode = entries.firstOrNull { it.name == value } ?: RANKED_SCORES
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
        fun fromStorage(value: String?): TimerMode = entries.firstOrNull { it.name == value } ?: TURN_BASED
    }
}

/** What happens when a player's bank timer runs out. */
enum class BankExhaustedBehaviour {
    /** Flag the player and count into negative overtime for the record. Default. */
    FLAG_AND_OVERTIME,

    /** Pass the turn automatically. */
    AUTO_PASS;

    companion object {
        fun fromStorage(value: String?): BankExhaustedBehaviour = entries.firstOrNull { it.name == value } ?: FLAG_AND_OVERTIME
    }
}

/** Lifecycle of the turn timer. Persisted so a process kill is recoverable. */
enum class TimerRunState {
    IDLE,
    RUNNING,
    PAUSED,
    STOPPED;

    companion object {
        fun fromStorage(value: String?): TimerRunState = entries.firstOrNull { it.name == value } ?: IDLE
    }
}

/** Which clock the active player is currently spending. */
enum class ActiveClock {
    TURN,
    BANK,
    OVERTIME;

    companion object {
        fun fromStorage(value: String?): ActiveClock = entries.firstOrNull { it.name == value } ?: TURN
    }
}

/** How a CSV import reconciles with existing data. */
enum class ImportMode { MERGE, REPLACE }

/** Theme preference. */
enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM;

    companion object {
        fun fromStorage(value: String?): ThemeMode = entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}
