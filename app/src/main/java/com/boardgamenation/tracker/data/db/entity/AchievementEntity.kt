package com.boardgamenation.tracker.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Definitions are seeded from a bundled JSON asset and reconciled on upgrade: new codes
 * are inserted and display text refreshed, but unlocks are never touched.
 */
@Entity(
    tableName = "achievements",
    indices = [Index(value = ["code"], unique = true)]
)
data class AchievementEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,

    /** Stable identifier from the JSON asset; the join key across app versions. */
    @ColumnInfo(name = "code") val code: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "description") val description: String,

    /** Material icon name, resolved at render time. */
    @ColumnInfo(name = "icon") val icon: String,
    @ColumnInfo(name = "category") val category: String,

    /** The number progress is measured against, when the rule has one. */
    @ColumnInfo(name = "target_value") val targetValue: Double? = null,

    /** Shown as "???" until unlocked. */
    @ColumnInfo(name = "is_hidden", defaultValue = "0") val isHidden: Boolean = false,
    @ColumnInfo(name = "sort_order", defaultValue = "0") val sortOrder: Int = 0,

    /** Serialised rule descriptor, evaluated by AchievementEvaluator. */
    @ColumnInfo(name = "rule_json") val ruleJson: String
)

/**
 * The unique index on achievement_id is what makes evaluation idempotent: a second
 * evaluation pass tries to insert the same row and is ignored.
 */
@Entity(
    tableName = "achievement_unlocks",
    foreignKeys = [
        ForeignKey(
            entity = AchievementEntity::class,
            parentColumns = ["id"],
            childColumns = ["achievement_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["achievement_id"], unique = true),
        Index(value = ["session_id"])
    ]
)
data class AchievementUnlockEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "achievement_id") val achievementId: Long,
    @ColumnInfo(name = "unlocked_at") val unlockedAt: Long,
    @ColumnInfo(name = "progress_value") val progressValue: Double,

    /** The session that tipped it over, when one did. */
    @ColumnInfo(name = "session_id") val sessionId: Long? = null
)
