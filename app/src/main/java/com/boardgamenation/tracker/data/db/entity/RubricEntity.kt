package com.boardgamenation.tracker.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A named set of scoring criteria, e.g. "Strategy" or "Cooperative". Criteria are rows
 * rather than columns so adding one never needs a migration.
 */
@Entity(tableName = "rubrics")
data class RubricEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "description") val description: String? = null,
    @ColumnInfo(name = "archived", defaultValue = "0") val archived: Boolean = false
)

@Entity(
    tableName = "rubric_criteria",
    foreignKeys = [
        ForeignKey(
            entity = RubricEntity::class,
            parentColumns = ["id"],
            childColumns = ["rubric_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["rubric_id"])]
)
data class RubricCriterionEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "rubric_id") val rubricId: Long,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "description") val description: String? = null,
    @ColumnInfo(name = "weight", defaultValue = "1.0") val weight: Double = 1.0,
    @ColumnInfo(name = "max_score", defaultValue = "10.0") val maxScore: Double = 10.0,
    @ColumnInfo(name = "sort_order", defaultValue = "0") val sortOrder: Int = 0
)

/**
 * One evaluation of one game against one rubric. Re-evaluations insert a new row rather
 * than overwriting, so a game's changing opinion of itself stays visible as history;
 * the newest row is treated as current.
 */
@Entity(
    tableName = "game_ratings",
    foreignKeys = [
        ForeignKey(
            entity = GameEntity::class,
            parentColumns = ["id"],
            childColumns = ["game_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = RubricEntity::class,
            parentColumns = ["id"],
            childColumns = ["rubric_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["game_id"]), Index(value = ["rubric_id"])]
)
data class GameRatingEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "game_id") val gameId: Long,
    @ColumnInfo(name = "rubric_id") val rubricId: Long,

    /** ISO-8601 date. */
    @ColumnInfo(name = "rated_on") val ratedOn: String,

    /** Normalised to a 0-10 scale and stored so list sorting needs no aggregate join. */
    @ColumnInfo(name = "computed_score") val computedScore: Double,
    @ColumnInfo(name = "notes") val notes: String? = null
)

@Entity(
    tableName = "game_rating_scores",
    foreignKeys = [
        ForeignKey(
            entity = GameRatingEntity::class,
            parentColumns = ["id"],
            childColumns = ["game_rating_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = RubricCriterionEntity::class,
            parentColumns = ["id"],
            childColumns = ["criterion_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["game_rating_id"]),
        Index(value = ["criterion_id"])
    ]
)
data class GameRatingScoreEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "game_rating_id") val gameRatingId: Long,
    @ColumnInfo(name = "criterion_id") val criterionId: Long,
    @ColumnInfo(name = "score") val score: Double
)
