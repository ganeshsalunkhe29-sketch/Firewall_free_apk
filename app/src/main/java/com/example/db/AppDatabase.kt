package com.example.db

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ==========================================
// Entities
// ==========================================

@Entity(tableName = "firewall_rules")
data class FirewallRule(
    @PrimaryKey val packageName: String,
    val appName: String,
    val isBlocked: Boolean,
    val isSystemApp: Boolean = false,
    val category: String = "Other"
)

@Entity(tableName = "vpn_profiles")
data class VpnProfile(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val profileName: String,
    val serverAddress: String,
    val serverPort: Int = 443,
    val dnsServer: String = "1.1.1.1",
    val mtu: Int = 1400,
    val username: String = "",
    val sharedSecret: String = "",
    val splitTunneling: Boolean = true,
    val isActive: Boolean = false
)

@Entity(tableName = "traffic_stats")
data class TrafficStatLog(
    @PrimaryKey val packageName: String,
    val totalSentBytes: Long = 0,
    val totalReceivedBytes: Long = 0,
    val lastUpdated: Long = System.currentTimeMillis()
)

@Entity(tableName = "firewall_logs")
data class FirewallLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appName: String,
    val destinationAddress: String,
    val port: Int,
    val protocol: String, // "TCP" or "UDP"
    val timestamp: Long = System.currentTimeMillis(),
    val isBlocked: Boolean,
    val isPending: Boolean = false
)

// ==========================================
// DAOs
// ==========================================

@Dao
interface FirewallDao {
    @Query("SELECT * FROM firewall_rules ORDER BY appName ASC")
    fun getAllRules(): Flow<List<FirewallRule>>

    @Query("SELECT * FROM firewall_rules WHERE isBlocked = 1")
    suspend fun getBlockedRules(): List<FirewallRule>

    @Query("SELECT * FROM firewall_rules WHERE packageName = :packageName")
    suspend fun getRuleByPackage(packageName: String): FirewallRule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: FirewallRule)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRules(rules: List<FirewallRule>)

    @Query("UPDATE firewall_rules SET isBlocked = :isBlocked WHERE packageName = :packageName")
    suspend fun updateBlockedStatus(packageName: String, isBlocked: Boolean)
    
    @Query("SELECT COUNT(*) FROM firewall_rules")
    suspend fun getRulesCount(): Int
}

@Dao
interface FirewallLogDao {
    @Query("SELECT * FROM firewall_logs ORDER BY timestamp DESC LIMIT 200")
    fun getAllLogs(): Flow<List<FirewallLog>>

    @Query("SELECT * FROM firewall_logs WHERE isPending = 1 ORDER BY timestamp DESC")
    fun getPendingLogs(): Flow<List<FirewallLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: FirewallLog): Long

    @Query("UPDATE firewall_logs SET isPending = :isPending, isBlocked = :isBlocked WHERE id = :logId")
    suspend fun updatePendingState(logId: Long, isPending: Boolean, isBlocked: Boolean)

    @Query("DELETE FROM firewall_logs")
    suspend fun clearLogs()

    @Query("DELETE FROM firewall_logs WHERE id = :logId")
    suspend fun deleteLog(logId: Long)
}

@Dao
interface VpnProfileDao {
    @Query("SELECT * FROM vpn_profiles ORDER BY id DESC")
    fun getAllProfiles(): Flow<List<VpnProfile>>

    @Query("SELECT * FROM vpn_profiles WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveProfile(): VpnProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: VpnProfile)

    @Update
    suspend fun updateProfile(profile: VpnProfile)

    @Delete
    suspend fun deleteProfile(profile: VpnProfile)

    @Transaction
    suspend fun setActiveProfile(profileId: Int) {
        clearActiveProfile()
        markProfileActive(profileId)
    }

    @Query("UPDATE vpn_profiles SET isActive = 0")
    suspend fun clearActiveProfile()

    @Query("UPDATE vpn_profiles SET isActive = 1 WHERE id = :profileId")
    suspend fun markProfileActive(profileId: Int)
}

@Dao
interface TrafficStatsDao {
    @Query("SELECT * FROM traffic_stats")
    fun getAllStats(): Flow<List<TrafficStatLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateStat(stat: TrafficStatLog)

    @Query("DELETE FROM traffic_stats")
    suspend fun clearStats()
}

// ==========================================
// Database
// ==========================================

@Database(
    entities = [FirewallRule::class, VpnProfile::class, TrafficStatLog::class, FirewallLog::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun firewallDao(): FirewallDao
    abstract fun vpnProfileDao(): VpnProfileDao
    abstract fun trafficStatsDao(): TrafficStatsDao
    abstract fun firewallLogDao(): FirewallLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vpn_firewall_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
