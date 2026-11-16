package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "games",
    indices = [Index(value = ["uriString"], unique = true)]
)
data class GameEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val serialId: String,
    val region: String,
    val formatLabel: String,
    val fileSizeBytes: Long,
    val uriString: String,
    val cueUriString: String? = null,
    val isFavorite: Boolean = false,
    val lastPlayedAt: Long = 0L,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "save_states",
    indices = [Index(value = ["gameId", "slotIndex"], unique = true)]
)
data class SaveStateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val gameId: Long,
    val slotIndex: Int,
    val statePath: String,
    val thumbnailPath: String? = null,
    val savedAt: Long = System.currentTimeMillis()
)
