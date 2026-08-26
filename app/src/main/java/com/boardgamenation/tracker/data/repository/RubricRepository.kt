package com.boardgamenation.tracker.data.repository

import com.boardgamenation.tracker.core.time.AppClock
import com.boardgamenation.tracker.core.time.DateUtils
import com.boardgamenation.tracker.data.db.dao.RubricDao
import com.boardgamenation.tracker.data.db.entity.GameRatingEntity
import com.boardgamenation.tracker.data.db.entity.GameRatingScoreEntity
import com.boardgamenation.tracker.data.db.entity.RubricCriterionEntity
import com.boardgamenation.tracker.data.db.entity.RubricEntity
import com.boardgamenation.tracker.data.db.projection.CriterionScoreRow
import com.boardgamenation.tracker.data.db.projection.RatingWithRubric
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RubricRepository @Inject constructor(
    private val rubricDao: RubricDao,
    private val clock: AppClock,
) {

    fun observeRubrics(): Flow<List<RubricEntity>> = rubricDao.observeActiveRubrics()

    fun observeCriteria(rubricId: Long): Flow<List<RubricCriterionEntity>> =
        rubricDao.observeCriteria(rubricId)

    fun observeRatingsFor(gameId: Long): Flow<List<RatingWithRubric>> =
        rubricDao.observeRatingsFor(gameId)

    fun observeCriterionScores(rubricId: Long, ratingId: Long): Flow<List<CriterionScoreRow>> =
        rubricDao.observeCriterionScores(rubricId, ratingId)

    suspend fun getRubric(id: Long): RubricEntity? = rubricDao.getRubric(id)

    suspend fun getCriteria(rubricId: Long): List<RubricCriterionEntity> =
        rubricDao.getCriteria(rubricId)

    suspend fun saveRubric(rubric: RubricEntity): Long =
        if (rubric.id == 0L) rubricDao.insertRubric(rubric) else {
            rubricDao.updateRubric(rubric)
            rubric.id
        }

    suspend fun setArchived(id: Long, archived: Boolean) = rubricDao.setRubricArchived(id, archived)

    suspend fun saveCriterion(criterion: RubricCriterionEntity): Long =
        if (criterion.id == 0L) rubricDao.insertCriterion(criterion) else {
            rubricDao.updateCriterion(criterion)
            criterion.id
        }

    suspend fun deleteCriterion(id: Long) = rubricDao.deleteCriterion(id)

    /**
     * Normalises a set of criterion scores onto a 0–10 scale.
     *
     * `Σ(score × weight) / Σ(weight × max_score) × 10`. Dividing by the weighted maximum
     * rather than the count is what lets two rubrics with different criteria, different
     * weights and different score ranges still be compared against each other, which is
     * the entire point of storing a computed score at all.
     */
    fun computeScore(
        criteria: List<RubricCriterionEntity>,
        scores: Map<Long, Double>,
    ): Double {
        val scored = criteria.filter { scores.containsKey(it.id) }
        if (scored.isEmpty()) return 0.0
        val numerator = scored.sumOf { (scores[it.id] ?: 0.0) * it.weight }
        val denominator = scored.sumOf { it.weight * it.maxScore }
        if (denominator <= 0.0) return 0.0
        return (numerator / denominator * 10.0).coerceIn(0.0, 10.0)
    }

    /**
     * Saves a rating.
     *
     * A new row every time rather than an update, so re-evaluating a game a year later
     * leaves the earlier opinion visible as history. The newest row is treated as current
     * everywhere else in the app.
     */
    suspend fun saveRating(
        gameId: Long,
        rubricId: Long,
        scores: Map<Long, Double>,
        notes: String?,
        existingRatingId: Long = 0,
    ): Long {
        val criteria = rubricDao.getCriteria(rubricId)
        val computed = computeScore(criteria, scores)
        val rating = GameRatingEntity(
            id = existingRatingId,
            gameId = gameId,
            rubricId = rubricId,
            ratedOn = DateUtils.toIso(clock.today()),
            computedScore = computed,
            notes = notes?.takeIf { it.isNotBlank() },
        )
        val rows = scores.map { (criterionId, score) ->
            GameRatingScoreEntity(
                gameRatingId = existingRatingId,
                criterionId = criterionId,
                score = score,
            )
        }
        return rubricDao.saveRating(rating, rows)
    }

    suspend fun deleteRating(id: Long) = rubricDao.deleteRating(id)

    suspend fun scoresFor(ratingId: Long): Map<Long, Double> =
        rubricDao.getScoresFor(ratingId).associate { it.criterionId to it.score }

    /**
     * Seeds the two rubrics the spec describes, once, on first run. Users can edit or
     * archive them; they are a starting point, not a fixture.
     */
    suspend fun seedDefaultsIfEmpty() {
        if (rubricDao.countRubrics() > 0) return

        val strategy = rubricDao.insertRubric(
            RubricEntity(
                name = "Strategy",
                description = "For games where the decisions carry the evening.",
            ),
        )
        rubricDao.insertCriteria(
            listOf(
                criterion(strategy, "Decision depth", "How much there is to think about.", 1.5, 0),
                criterion(strategy, "Replayability", "Does it change between plays?", 1.5, 1),
                criterion(strategy, "Downtime", "How well it holds attention between turns.", 1.0, 2),
                criterion(strategy, "Interaction", "How much the players affect each other.", 1.0, 3),
                criterion(strategy, "Components", "Table presence and physical quality.", 0.5, 4),
                criterion(strategy, "Rules clarity", "How well it teaches and reference.", 1.0, 5),
            ),
        )

        val coop = rubricDao.insertRubric(
            RubricEntity(
                name = "Cooperative",
                description = "For games the table wins or loses together.",
            ),
        )
        rubricDao.insertCriteria(
            listOf(
                criterion(coop, "Tension", "Does the difficulty stay meaningful?", 1.5, 0),
                criterion(coop, "Team decisions", "Everyone contributes, nobody is a passenger.", 1.5, 1),
                criterion(coop, "Variability", "Do the scenarios stay fresh?", 1.0, 2),
                criterion(coop, "Theme", "How well the story lands.", 1.0, 3),
                criterion(coop, "Components", "Table presence and physical quality.", 0.5, 4),
            ),
        )
    }

    private fun criterion(
        rubricId: Long,
        name: String,
        description: String,
        weight: Double,
        order: Int,
    ) = RubricCriterionEntity(
        rubricId = rubricId,
        name = name,
        description = description,
        weight = weight,
        maxScore = 10.0,
        sortOrder = order,
    )
}
