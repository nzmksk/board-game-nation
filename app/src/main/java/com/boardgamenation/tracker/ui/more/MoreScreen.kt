package com.boardgamenation.tracker.ui.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.boardgamenation.tracker.R

/** The overflow destination for everything that does not earn a bottom-bar slot. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    bggEnabled: Boolean,
    onDashboard: () -> Unit,
    onAchievements: () -> Unit,
    onPlayers: () -> Unit,
    onRubrics: () -> Unit,
    onBggImport: () -> Unit,
    onSettings: () -> Unit
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_more)) }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            item {
                MoreRow(Icons.Filled.Home, stringResource(R.string.dashboard_title), onDashboard)
            }
            item {
                MoreRow(
                    Icons.Filled.EmojiEvents,
                    stringResource(R.string.achievements_title),
                    onAchievements
                )
            }
            item {
                MoreRow(Icons.Filled.Group, stringResource(R.string.players_title), onPlayers)
            }
            item {
                MoreRow(Icons.Filled.Star, stringResource(R.string.rubrics_title), onRubrics)
            }
            if (bggEnabled) {
                item {
                    MoreRow(
                        Icons.Filled.CloudDownload,
                        stringResource(R.string.bgg_import_title),
                        onBggImport
                    )
                }
            }
            item {
                MoreRow(Icons.Filled.Settings, stringResource(R.string.settings_title), onSettings)
            }
        }
    }
}

@Composable
private fun MoreRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
