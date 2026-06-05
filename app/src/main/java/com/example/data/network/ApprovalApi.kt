package com.example.data.network

import com.example.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class ApprovalRequest(
    val id: String,
    val status: String,
    val minutes: Int,
    val approvalUrl: String,
)

class ApprovalApi {
    private val client = OkHttpClient()
    private val baseUrl = BuildConfig.SERVER_URL.trimEnd('/')
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun createRequest(minutes: Int = 15, approverPhone: String? = null): ApprovalRequest {
        val bodyObj = JSONObject().put("minutes", minutes)
        if (approverPhone != null) {
            bodyObj.put("approverPhone", approverPhone)
        }
        val body = bodyObj.toString()
        val request = Request.Builder()
            .url("$baseUrl/api/requests")
            .post(body.toRequestBody(jsonType))
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
            approvalUrl = json.getString("approvalUrl"),
        )
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
