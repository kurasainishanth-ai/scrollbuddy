package com.example.data.repository

import com.example.data.local.DailyUsage
import com.example.data.local.Friend
import com.example.data.local.ScrollRequest
import com.example.data.local.ScrollSentryDao
import kotlinx.coroutines.flow.Flow

class ScrollSentryRepository(private val dao: ScrollSentryDao) {
    fun getLatestDailyUsageFlow(): Flow<DailyUsage?> = dao.getLatestDailyUsageFlow()

    fun getDailyUsageFlow(date: String): Flow<DailyUsage?> = dao.getDailyUsageFlow(date)
    
    suspend fun getDailyUsageDirect(date: String): DailyUsage? = dao.getDailyUsageDirect(date)
    
    suspend fun saveDailyUsage(dailyUsage: DailyUsage) {
        dao.insertDailyUsage(dailyUsage)
    }
    
    fun getFriendsFlow(): Flow<List<Friend>> = dao.getFriendsFlow()
    
    suspend fun getFriendsDirect(): List<Friend> = dao.getFriendsDirect()
    
    suspend fun insertFriend(friend: Friend) {
        dao.insertFriend(friend)
    }
    
    fun getRequestsFlow(): Flow<List<ScrollRequest>> = dao.getRequestsFlow()
    
    suspend fun insertRequest(request: ScrollRequest): Long {
        return dao.insertRequest(request)
    }
    
    suspend fun updateRequestStatus(id: Long, status: String) {
        dao.updateRequestStatus(id, status)
    }

    suspend fun getRequestById(id: Long): ScrollRequest? {
        return dao.getRequestById(id)
    }

    suspend fun getRequestByUuid(uuid: String): ScrollRequest? {
        return dao.getRequestByUuid(uuid)
    }

    suspend fun getRequestByServerId(serverRequestId: String): ScrollRequest? {
        return dao.getRequestByServerId(serverRequestId)
    }
}
