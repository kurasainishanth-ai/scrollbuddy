package com.example.data.network

import com.example.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ApprovalRequest(
    val id: String,
    val status: String,
    val minutes: Int,
    val approvalUrl: String,
)

class ApprovalApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val baseUrl = BuildConfig.SERVER_URL.trimEnd('/')
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun createRequest(minutes: Int = 15): ApprovalRequest {
        val body = JSONObject().put("minutes", minutes).toString()
        val request = Request.Builder()
            .url("$baseUrl/api/requests")
            .post(body.toRequestBody(jsonType))
            .build()
        
        val response = client.newCall(request).execute()
        val text = response.body?.string().orEmpty()
        
        if (!response.isSuccessful) {
            throw IllegalStateException("Server error ${response.code}: $text")
        }
        
        return try {
            val json = JSONObject(text)
            ApprovalRequest(
                id = json.getString("id"),
                status = json.getString("status"),
                minutes = json.getInt("minutes"),
                approvalUrl = json.getString("approvalUrl"),
            )
        } catch (e: Exception) {
            throw IllegalStateException("Invalid server response format: $text", e)
        }
    }

    fun getStatus(requestId: String): ApprovalRequest {
        val request = Request.Builder()
            .url("$baseUrl/api/requests/$requestId")
            .get()
            .build()
        val response = client.newCall(request).execute()
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw IllegalStateException("Server error ${response.code}: $text")
        }
        val json = JSONObject(text)
        return ApprovalRequest(
            id = json.getString("id"),
            status = json.getString("status"),
            minutes = json.getInt("minutes"),
            approvalUrl = "",
        )
    }
}
