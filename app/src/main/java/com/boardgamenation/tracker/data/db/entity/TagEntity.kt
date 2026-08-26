package com.boardgamenation.tracker.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.boardgamenation.tracker.domain.model.TagKind

@Entity(
    tableName = "tags",
    indices = [Index(value = ["name", "kind"], unique = true)],
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "kind") val kind: TagKind,
)

@Entity(
    tableName = "game_tags",
    primaryKeys = ["game_id", "tag_id"],
    foreignKeys = [
        ForeignKey(
            entity = GameEntity::class,
            parentColumns = ["id"],
            childColumns = ["game_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["tag_id"])],
)
data class GameTagCrossRef(
    @ColumnInfo(name = "game_id") val gameId: Long,
    @ColumnInfo(name = "tag_id") val tagId: Long,
)
