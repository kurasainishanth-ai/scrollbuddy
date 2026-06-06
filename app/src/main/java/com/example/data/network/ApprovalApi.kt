package com.example.data.network

import android.util.Log
import com.example.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class BackendRequest(
    val id: String,
    val requester: String,
    val approver: String,
    val minutes: Int,
    val status: String,
)

data class BackendUser(
    val username: String
)

class ApprovalApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
        
    private val baseUrl = BuildConfig.SERVER_URL.trimEnd('/')
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun registerUser(username: String): BackendUser {
        val endpoint = "$baseUrl/api/register"
        Log.d("ScrollSentryAPI", "Registering user: $username at $endpoint")
        val body = JSONObject().put("username", username).toString()
        val request = Request.Builder()
            .url(endpoint)
            .post(body.toRequestBody(jsonType))
            .build()
        
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: "{}"
        Log.d("ScrollSentryAPI", "Register response code: ${response.code}")
        Log.d("ScrollSentryAPI", "Register response body: $responseBody")
        
        if (!response.isSuccessful) {
            val errorMsg = try {
                JSONObject(responseBody).getString("error")
            } catch (e: Exception) {
                "Registration failed (Code: ${response.code})"
            }
            throw IllegalStateException(errorMsg)
        }
        val json = JSONObject(responseBody)
        return BackendUser(json.getString("username"))
    }

    fun searchUsers(query: String, exclude: String): List<BackendUser> {
        val url = "$baseUrl/api/users/search?q=${query.trim().lowercase()}&exclude=${exclude.trim().lowercase()}"
        Log.d("ScrollSentryAPI", "Searching users at: $url")
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: "[]"
        Log.d("ScrollSentryAPI", "Search response (${response.code}): $responseBody")
        
        if (!response.isSuccessful) return emptyList()
        val arr = JSONArray(responseBody)
        val list = mutableListOf<BackendUser>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(BackendUser(obj.getString("username")))
        }
        return list
    }

    fun createRequest(requester: String, approver: String, minutes: Int): BackendRequest {
        Log.d("ScrollSentryAPI", "Creating request from $requester to $approver")
        val body = JSONObject()
            .put("requester", requester)
            .put("approver", approver)
            .put("minutes", minutes)
            .toString()
        val request = Request.Builder()
            .url("$baseUrl/api/requests")
            .post(body.toRequestBody(jsonType))
            .build()
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: "{}"
        Log.d("ScrollSentryAPI", "Create request response (${response.code}): $responseBody")
        
        if (!response.isSuccessful) throw IllegalStateException("Request failed")
        val json = JSONObject(responseBody)
        return BackendRequest(
            id = json.getString("id"),
            requester = json.getString("requester"),
            approver = json.getString("approver"),
            minutes = json.getInt("minutes"),
            status = json.getString("status")
        )
    }

    fun getInbox(username: String): List<BackendRequest> {
        val request = Request.Builder()
            .url("$baseUrl/api/requests/inbox/$username")
            .get()
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return emptyList()
        val responseBody = response.body?.string() ?: "[]"
        val arr = JSONArray(responseBody)
        val list = mutableListOf<BackendRequest>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(BackendRequest(
                id = obj.getString("id"),
                requester = obj.getString("requester"),
                approver = obj.getString("approver"),
                minutes = obj.getInt("minutes"),
                status = obj.getString("status")
            ))
        }
        return list
    }

    fun updateRequestStatus(requestId: String, status: String): BackendRequest {
        val body = JSONObject().put("status", status).toString()
        val request = Request.Builder()
            .url("$baseUrl/api/requests/$requestId/decision")
            .post(body.toRequestBody(jsonType))
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw IllegalStateException("Decision failed")
        val json = JSONObject(response.body?.string() ?: "{}")
        return BackendRequest(
            id = json.getString("id"),
            requester = json.getString("requester"),
            approver = json.getString("approver"),
            minutes = json.getInt("minutes"),
            status = json.getString("status")
        )
    }

    fun getStatus(requestId: String): BackendRequest {
        val request = Request.Builder()
            .url("$baseUrl/api/requests/$requestId")
            .get()
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw IllegalStateException("Status check failed")
        val json = JSONObject(response.body?.string() ?: "{}")
        return BackendRequest(
            id = json.getString("id"),
            requester = json.getString("requester"),
            approver = json.getString("approver"),
            minutes = json.getInt("minutes"),
            status = json.getString("status")
        )
    }
}
