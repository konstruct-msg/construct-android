package com.construct.messenger.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "issued_invites")
data class IssuedInviteEntity(
    @PrimaryKey val jti: String,
    val kind: String,
    val issuedAtEpochSec: Long,
    val ttlSeconds: Int,
)

@Dao
interface IssuedInviteDao {
    @Upsert
    suspend fun upsert(row: IssuedInviteEntity)

    @Query("SELECT * FROM issued_invites ORDER BY issuedAtEpochSec DESC")
    fun observeAll(): Flow<List<IssuedInviteEntity>>

    @Query("SELECT * FROM issued_invites")
    suspend fun getAll(): List<IssuedInviteEntity>

    @Query("DELETE FROM issued_invites WHERE jti = :jti")
    suspend fun delete(jti: String)
}
