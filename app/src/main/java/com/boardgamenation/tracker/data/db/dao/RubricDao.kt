package com.boardgamenation.tracker.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.boardgamenation.tracker.data.db.entity.GameRatingEntity
import com.boardgamenation.tracker.data.db.entity.GameRatingScoreEntity
import com.boardgamenation.tracker.data.db.entity.RubricCriterionEntity
import com.boardgamenation.tracker.data.db.entity.RubricEntity
import com.boardgamenation.tracker.data.db.projection.CriterionScoreRow
import com.boardgamenation.tracker.data.db.projection.RatingWithRubric
import kotlinx.coroutines.flow.Flow

@Dao
interface RubricDao {

    @Query("SELECT * FROM rubrics WHERE archived = 0 ORDER BY name COLLATE NOCASE")
    fun observeActiveRubrics(): Flow<List<RubricEntity>>

    @Query("SELECT * FROM rubrics ORDER BY name COLLATE NOCASE")
    suspend fun getAllRubrics(): List<RubricEntity>

    @Query("SELECT * FROM rubrics WHERE id = :id")
    suspend fun getRubric(id: Long): RubricEntity?

    @Query("SELECT * FROM rubrics WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findRubricByName(name: String): RubricEntity?

    @Query("SELECT * FROM rubric_criteria WHERE rubric_id = :rubricId ORDER BY sort_order, id")
    fun observeCriteria(rubricId: Long): Flow<List<RubricCriterionEntity>>

    @Query("SELECT * FROM rubric_criteria WHERE rubric_id = :rubricId ORDER BY sort_order, id")
    suspend fun getCriteria(rubricId: Long): List<RubricCriterionEntity>

    @Query("SELECT * FROM rubric_criteria ORDER BY rubric_id, sort_order, id")
    suspend fun getAllCriteria(): List<RubricCriterionEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRubric(rubric: RubricEntity): Long

    @Update
    suspend fun updateRubric(rubric: RubricEntity)

    @Query("UPDATE rubrics SET archived = :archived WHERE id = :id")
    suspend fun setRubricArchived(id: Long, archived: Boolean)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCriterion(criterion: RubricCriterionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCriteria(criteria: List<RubricCriterionEntity>): List<Long>

    @Update
    suspend fun updateCriterion(criterion: RubricCriterionEntity)

    @Query("DELETE FROM rubric_criteria WHERE id = :id")
    suspend fun deleteCriterion(id: Long)

    // --- ratings ------------------------------------------------------------------

    /**
     * Every rating a game has ever had, newest first. Re-evaluations are kept rather
     * than overwritten, so the first row is "current" and the rest are the history.
     */
    @Query(
        """
        SELECT
            r.id AS rating_id, r.rubric_id, rb.name AS rubric_name,
            r.rated_on, r.computed_score, r.notes
        FROM game_ratings r
        JOIN rubrics rb ON rb.id = r.rubric_id
        WHERE r.game_id = :gameId
        ORDER BY r.rated_on DESC, r.id DESC
        """,
    )
    fun observeRatingsFor(gameId: Long): Flow<List<RatingWithRubric>>

    @Query("SELECT * FROM game_ratings ORDER BY game_id, rated_on")
    suspend fun getAllRatings(): List<GameRatingEntity>

    @Query("SELECT * FROM game_ratings WHERE id = :id")
    suspend fun getRating(id: Long): GameRatingEntity?

    /**
     * The criteria of a rubric with whatever scores a given rating assigned them.
     * Left-joined so a part-filled rating still renders every criterion.
     */
    @Query(
        """
        SELECT
            c.id AS criterion_id, c.name AS criterion_name, c.description,
            c.weight, c.max_score, c.sort_order, s.score
        FROM rubric_criteria c
        LEFT JOIN game_rating_scores s
               ON s.criterion_id = c.id AND s.game_rating_id = :ratingId
        WHERE c.rubric_id = :rubricId
        ORDER BY c.sort_order, c.id
        """,
    )
    fun observeCriterionScores(rubricId: Long, ratingId: Long): Flow<List<CriterionScoreRow>>

    @Query("SELECT * FROM game_rating_scores WHERE game_rating_id = :ratingId")
    suspend fun getScoresFor(ratingId: Long): List<GameRatingScoreEntity>

    @Query("SELECT * FROM game_rating_scores")
    suspend fun getAllScores(): List<GameRatingScoreEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRating(rating: GameRatingEntity): Long

    @Update
    suspend fun updateRating(rating: GameRatingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScores(scores: List<GameRatingScoreEntity>)

    @Query("DELETE FROM game_rating_scores WHERE game_rating_id = :ratingId")
    suspend fun clearScores(ratingId: Long)

    @Query("DELETE FROM game_ratings WHERE id = :id")
    suspend fun deleteRating(id: Long)

    /**
     * Persists a rating and its per-criterion scores together, with [computedScore]
     * already normalised, so sorting the collection by rating never needs a join.
     */
    @Transaction
    suspend fun saveRating(
        rating: GameRatingEntity,
        scores: List<GameRatingScoreEntity>,
    ): Long {
        val id = if (rating.id == 0L) {
            insertRating(rating)
        } else {
            updateRating(rating)
            rating.id
        }
        clearScores(id)
        insertScores(scores.map { it.copy(id = 0, gameRatingId = id) })
        return id
    }

    @Query("SELECT COUNT(*) FROM rubrics")
    suspend fun countRubrics(): Int

    @Query("SELECT COUNT(*) FROM rubric_criteria")
    suspend fun countCriteria(): Int

    @Query("SELECT COUNT(*) FROM game_ratings")
    suspend fun countRatings(): Int

    @Query("SELECT COUNT(*) FROM game_rating_scores")
    suspend fun countScores(): Int

    @Query("DELETE FROM rubrics")
    suspend fun deleteAllRubrics()

    @Query("DELETE FROM game_ratings")
    suspend fun deleteAllRatings()
}
