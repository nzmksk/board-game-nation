package com.boardgamenation.tracker.data.repository

import android.content.Context
import com.boardgamenation.tracker.data.db.dao.AchievementDao
import com.boardgamenation.tracker.data.db.dao.AchievementStatsDao
import com.boardgamenation.tracker.data.db.entity.AchievementEntity
import com.boardgamenation.tracker.di.IoDispatcher
import com.boardgamenation.tracker.domain.achievement.AchievementDefinition
import com.boardgamenation.tracker.domain.achievement.AchievementEvaluator
import com.boardgamenation.tracker.domain.achievement.RuleProgress
import com.boardgamenation.tracker.domain.achievement.RuleType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** One achievement as the grid renders it. */
data class AchievementUi(
    val id: Long,
    val code: String,
    val name: String,
    val description: String,
    val icon: String,
    val category: String,
    val isHidden: Boolean,
    val unlockedAt: Long?,
    val progress: RuleProgress
) {
    val isUnlocked: Boolean get() = unlockedAt != null

    /**
     * Hidden achievements keep their secret until earned. The placeholder text itself
     * lives in strings.xml and is chosen by the composable; this only states whether it
     * should be used.
     */
    val isSecret: Boolean get() = isHidden && !isUnlocked
}

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class AchievementRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val achievementDao: AchievementDao,
    private val statsDao: AchievementStatsDao,
    private val evaluator: AchievementEvaluator,
    @param:IoDispatcher private val io: CoroutineDispatcher
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Loads definitions from the bundled asset.
     *
     * Reconciliation, not replacement: new codes are inserted, existing ones have their
     * display text and rule refreshed, and nothing is ever deleted. An unlock earned two
     * app versions ago survives a rewording of the achievement that earned it.
     */
    suspend fun seedFromAsset() = withContext(io) {
        val definitions = readDefinitions()
        if (definitions.isEmpty()) return@withContext

        val existingCodes = achievementDao.getAllDefinitions().associateBy { it.code }

        val newRows = definitions.filter { it.code !in existingCodes }.map { it.toEntity() }
        if (newRows.isNotEmpty()) achievementDao.insertDefinitions(newRows)

        definitions.filter { it.code in existingCodes }.forEach { definition ->
            achievementDao.updateDefinitionByCode(
                code = definition.code,
                name = definition.name,
                description = definition.description,
                icon = definition.icon,
                category = definition.category,
                targetValue = definition.rule.target.takeIf { it > 0.0 },
                isHidden = definition.hidden,
                sortOrder = definition.sortOrder,
                ruleJson = json.encodeToString(definition.rule)
            )
        }
    }

    private fun readDefinitions(): List<AchievementDefinition> = try {
        context.assets.open(ASSET_NAME).use { stream ->
            json.decodeFromString<List<AchievementDefinition>>(stream.readBytes().decodeToString())
        }
    } catch (_: Exception) {
        // A broken asset should not stop the app booting; it just means no achievements.
        emptyList()
    }

    /**
     * The achievements grid. Re-emits whenever anything an achievement could depend on
     * changes, so progress bars move as sessions are logged without any manual refresh.
     */
    fun observeAchievements(): Flow<List<AchievementUi>> = combine(
        achievementDao.observeAll(),
        statsDao.observeInvalidationToken()
    ) { rows, _ -> rows }
        .mapLatest { rows ->
            val rules = rows.associate { it.code to evaluator.parseRule(it.ruleJson) }
            val minPlays = rules.values
                .filter { it.type == RuleType.RATIO }
                .map { it.minPlays }
                .toSet()
            val snapshot = evaluator.snapshot(minPlays)
            rows.map { row ->
                val rule = rules.getValue(row.code)
                AchievementUi(
                    id = row.id,
                    code = row.code,
                    name = row.name,
                    description = row.description,
                    icon = row.icon,
                    category = row.category,
                    isHidden = row.isHidden,
                    unlockedAt = row.unlockedAt,
                    progress = evaluator.progressOf(rule, snapshot)
                )
            }
        }

    fun observeUnlockedCount(): Flow<Int> = achievementDao.observeUnlockedCount()

    fun observeTotalCount(): Flow<Int> = achievementDao.observeTotalCount()

    fun observeRecentlyUnlocked(limit: Int = 3) = achievementDao.observeRecentlyUnlocked(limit)

    /** Called after a session is saved. Returns whatever it newly unlocked. */
    suspend fun evaluateAfterSession(sessionId: Long?): List<AchievementEntity> = withContext(io) { evaluator.evaluate(sessionId) }

    /** Called after an edit or delete, which can invalidate an existing unlock. */
    suspend fun reconcile() = withContext(io) { evaluator.reconcile() }

    companion object {
        private const val ASSET_NAME = "achievements.json"
    }

    private fun AchievementDefinition.toEntity() = AchievementEntity(
        code = code,
        name = name,
        description = description,
        icon = icon,
        category = category,
        // A target of zero means the rule is a yes/no condition with nothing to show a
        // progress bar for, so the column stays null rather than displaying "0 of 0".
        targetValue = rule.target.takeIf { it > 0.0 },
        isHidden = hidden,
        sortOrder = sortOrder,
        ruleJson = json.encodeToString(rule)
    )
}
