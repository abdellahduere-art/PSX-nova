package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface GameDao {
    @Query("SELECT * FROM games ORDER BY lastPlayedAt DESC, addedAt DESC")
    fun observeAllGames(): Flow<List<GameEntity>>

    @Query("SELECT * FROM games WHERE id = :id LIMIT 1")
    suspend fun getGameById(id: Long): GameEntity?

    @Query("SELECT * FROM games WHERE uriString = :uriString LIMIT 1")
    suspend fun getGameByUri(uriString: String): GameEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGame(game: GameEntity): Long

    @Query("UPDATE games SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("UPDATE games SET lastPlayedAt = :timestamp WHERE id = :id")
    suspend fun updateLastPlayed(id: Long, timestamp: Long)

    @Query("DELETE FROM games WHERE id = :id")
    suspend fun deleteGame(id: Long)
}

@Dao
interface SaveStateDao {
    @Query("SELECT * FROM save_states WHERE gameId = :gameId ORDER BY slotIndex ASC")
    fun observeStatesForGame(gameId: Long): Flow<List<SaveStateEntity>>

    @Query("SELECT * FROM save_states WHERE gameId = :gameId AND slotIndex = :slot LIMIT 1")
    suspend fun getState(gameId: Long, slot: Int): SaveStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(state: SaveStateEntity)

    @Query("DELETE FROM save_states WHERE gameId = :gameId AND slotIndex = :slot")
    suspend fun deleteState(gameId: Long, slot: Int)
}

@Database(
    entities = [GameEntity::class, SaveStateEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun gameDao(): GameDao
    abstract fun saveStateDao(): SaveStateDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "psx_nova_db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
