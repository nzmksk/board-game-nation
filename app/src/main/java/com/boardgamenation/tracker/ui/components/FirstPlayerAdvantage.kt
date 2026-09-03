package com.boardgamenation.tracker.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.boardgamenation.tracker.R
import com.boardgamenation.tracker.data.db.projection.FirstPlayerRecord

/**
 * How the first seat has fared, against the baseline chance would have given it.
 *
 * Two bars rather than one figure. A win rate for the starting player is unreadable on
 * its own -- 40% is a rout at a table of five and a losing record at a table of two --
 * so the rate is drawn beside what the seat would have won if it meant nothing, and the
 * comparison is made without asking anybody to do the arithmetic.
 *
 * The scale is fixed at 0-100 rather than taken from the two rows, so a 30% reads as
 * under a third of the width instead of as a full bar beside a shorter one.
 *
 * The line underneath carries the gap in percentage points and the sample it came from,
 * because a 20-point edge over four plays and one over eighty are the same number and
 * not the same claim.
 */
@Composable
fun FirstPlayerAdvantage(record: FirstPlayerRecord, modifier: Modifier = Modifier) {
    val expected = record.expectedPercent
    val edge = record.edgePoints
    if (record.plays == 0 || expected == null || edge == null) {
        Text(
            text = stringResource(R.string.stats_first_player_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
        )
        return
    }

    Column(modifier) {
        HorizontalBarChart(
            data = listOf(
                stringResource(R.string.stats_first_player_actual) to record.winPercent.toDouble(),
                stringResource(R.string.stats_first_player_chance) to expected.toDouble()
            ),
            valueLabels = listOf(
                stringResource(R.string.stats_first_player_percent, record.winPercent),
                stringResource(R.string.stats_first_player_percent, expected)
            ),
            valueWidth = 40.dp,
            scaleMax = 100.0
        )
        Spacer(Modifier.height(8.dp))
        // The run of plays is a count of its own, so it is worded first and dropped into
        // the sentence: one plural cannot inflect two numbers.
        val plays = pluralStringResource(R.plurals.stats_plays_value, record.plays, record.plays)
        Text(
            text = when {
                edge > 0 -> pluralStringResource(
                    R.plurals.stats_first_player_edge_ahead,
                    edge,
                    edge,
                    plays
                )

                // The sign is in the wording, so the count that picks the plural form and
                // the number in it are both the size of the gap rather than -edge.
                edge < 0 -> pluralStringResource(
                    R.plurals.stats_first_player_edge_behind,
                    -edge,
                    -edge,
                    plays
                )

                else -> pluralStringResource(
                    R.plurals.stats_first_player_edge_level,
                    record.plays,
                    record.plays
                )
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
