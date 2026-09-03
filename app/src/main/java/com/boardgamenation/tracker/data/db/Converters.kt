package com.boardgamenation.tracker.data.db

import androidx.room.TypeConverter
import com.boardgamenation.tracker.domain.model.ActiveClock
import com.boardgamenation.tracker.domain.model.BankExhaustedBehaviour
import com.boardgamenation.tracker.domain.model.CoopOutcome
import com.boardgamenation.tracker.domain.model.GameStatus
import com.boardgamenation.tracker.domain.model.ScoringMode
import com.boardgamenation.tracker.domain.model.SessionEndCondition
import com.boardgamenation.tracker.domain.model.TagKind
import com.boardgamenation.tracker.domain.model.TimerMode
import com.boardgamenation.tracker.domain.model.TimerRunState

/**
 * Enums are stored as their names rather than ordinals, so reordering an enum can never
 * silently reinterpret existing rows and the CSV export stays human-readable.
 */
class Converters {
    @TypeConverter fun gameStatusToString(v: GameStatus): String = v.name

    @TypeConverter fun stringToGameStatus(v: String): GameStatus = GameStatus.fromStorage(v)

    @TypeConverter fun tagKindToString(v: TagKind): String = v.name

    @TypeConverter fun stringToTagKind(v: String): TagKind = TagKind.fromStorage(v)

    @TypeConverter fun coopOutcomeToString(v: CoopOutcome?): String? = v?.name

    @TypeConverter fun stringToCoopOutcome(v: String?): CoopOutcome? = CoopOutcome.fromStorage(v)

    @TypeConverter fun scoringModeToString(v: ScoringMode): String = v.name

    @TypeConverter fun stringToScoringMode(v: String): ScoringMode = ScoringMode.fromStorage(v)

    @TypeConverter fun endConditionToString(v: SessionEndCondition?): String? = v?.name

    @TypeConverter fun stringToEndCondition(v: String?): SessionEndCondition? = SessionEndCondition.fromStorage(v)

    @TypeConverter fun timerModeToString(v: TimerMode): String = v.name

    @TypeConverter fun stringToTimerMode(v: String): TimerMode = TimerMode.fromStorage(v)

    @TypeConverter fun runStateToString(v: TimerRunState): String = v.name

    @TypeConverter fun stringToRunState(v: String): TimerRunState = TimerRunState.fromStorage(v)

    @TypeConverter fun activeClockToString(v: ActiveClock): String = v.name

    @TypeConverter fun stringToActiveClock(v: String): ActiveClock = ActiveClock.fromStorage(v)

    @TypeConverter fun bankBehaviourToString(v: BankExhaustedBehaviour): String = v.name

    @TypeConverter fun stringToBankBehaviour(v: String): BankExhaustedBehaviour = BankExhaustedBehaviour.fromStorage(v)
}
