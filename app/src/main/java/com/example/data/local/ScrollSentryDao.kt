package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ScrollSentryDao {
    @Query("SELECT * FROM daily_usage ORDER BY date DESC LIMIT 1")
    fun getLatestDailyUsageFlow(): Flow<DailyUsage?>

    @Query("SELECT * FROM daily_usage WHERE date = :date LIMIT 1")
    fun getDailyUsageFlow(date: String): Flow<DailyUsage?>

    @Query("SELECT * FROM daily_usage WHERE date = :date LIMIT 1")
    suspend fun getDailyUsageDirect(date: String): DailyUsage?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDailyUsage(dailyUsage: DailyUsage)

    @Query("SELECT * FROM friends")
    fun getFriendsFlow(): Flow<List<Friend>>

    @Query("SELECT * FROM friends")
    suspend fun getFriendsDirect(): List<Friend>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFriend(friend: Friend)

    @Query("SELECT * FROM scroll_requests ORDER BY timestamp DESC")
    fun getRequestsFlow(): Flow<List<ScrollRequest>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRequest(request: ScrollRequest): Long

    @Query("UPDATE scroll_requests SET status = :status WHERE id = :id")
    suspend fun updateRequestStatus(id: Long, status: String)

    @Query("SELECT * FROM scroll_requests WHERE id = :id")
    suspend fun getRequestById(id: Long): ScrollRequest?

    @Query("SELECT * FROM scroll_requests WHERE uuid = :uuid LIMIT 1")
    suspend fun getRequestByUuid(uuid: String): ScrollRequest?

    @Query("SELECT * FROM scroll_requests WHERE serverRequestId = :serverRequestId LIMIT 1")
    suspend fun getRequestByServerId(serverRequestId: String): ScrollRequest?

    // User Account
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setUserAccount(user: UserAccount)

    @Query("SELECT * FROM user_account LIMIT 1")
    suspend fun getUserAccount(): UserAccount?

    @Query("DELETE FROM user_account")
    suspend fun clearUserAccount()
}
