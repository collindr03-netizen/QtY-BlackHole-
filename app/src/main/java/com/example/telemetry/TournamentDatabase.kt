package com.example.telemetry

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ==========================================
// ROOM ENTITY: TOURNAMENT RUN RECORD
// ==========================================

@Entity(tableName = "tournament_runs")
data class TournamentRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestampMs: Long,
    val strategyId: String,
    val datasetName: String,
    val topDecilePrecision: Double,
    val brierScore: Double,
    val expectedCalibrationError: Double,
    val edgeToCostRatio: Double,
    val incrementalBss: Double,
    val cumulativeNetPnL: Double,
    val maxDrawdown: Double,
    val winRate: Double,
    val totalTrades: Int
)

// ==========================================
// ROOM DAO: TOURNAMENT RUN DATA ACCESS
// ==========================================

@Dao
interface TournamentRunDao {
    @Query("SELECT * FROM tournament_runs ORDER BY timestampMs DESC")
    fun getAllRuns(): Flow<List<TournamentRunEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRun(run: TournamentRunEntity)

    @Query("DELETE FROM tournament_runs")
    suspend fun clearAllRuns()
}

// ==========================================
// ROOM DATABASE HOLDER
// ==========================================

@Database(entities = [TournamentRunEntity::class], version = 1, exportSchema = false)
abstract class TournamentDatabase : RoomDatabase() {
    abstract fun tournamentRunDao(): TournamentRunDao

    companion object {
        @Volatile
        private var INSTANCE: TournamentDatabase? = null

        fun getDatabase(context: Context): TournamentDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TournamentDatabase::class.java,
                    "tournament_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// ==========================================
// REPOSITORY PATTERN ARCHITECTURE
// ==========================================

class TournamentRepository(private val dao: TournamentRunDao) {
    val allRuns: Flow<List<TournamentRunEntity>> = dao.getAllRuns()

    suspend fun insert(run: TournamentRunEntity) {
        dao.insertRun(run)
    }

    suspend fun clear() {
        dao.clearAllRuns()
    }
}
