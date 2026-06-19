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
    val type: String = "EXTENSION_REQUEST",
    val reason: String? = null,
    val createdAt: Long? = null,
)

data class BackendUser(
    val username: String,
    val googleUid: String? = null,
    val email: String? = null,
    val displayName: String? = null,
    val photoUrl: String? = null
)

class ApprovalApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
        
    private val baseUrl = BuildConfig.SERVER_URL.trimEnd('/')
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun authenticateGoogle(idToken: String, username: String? = null): BackendUser {
        val endpoint = "$baseUrl/api/auth/google"
        Log.d("ScrollSentryAPI", "Authenticating with Google at $endpoint")
        
        val bodyObj = JSONObject().put("idToken", idToken)
        if (username != null) bodyObj.put("username", username)
        
        val request = Request.Builder()
            .url(endpoint)
            .post(bodyObj.toString().toRequestBody(jsonType))
            .build()
            
        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            Log.e("ScrollSentryAPI", "Network error calling $endpoint", e)
            throw IllegalStateException("Network timeout or connection failed to $endpoint")
        }

        val responseBody = response.body?.string() ?: "{}"
        Log.d("ScrollSentryAPI", "Auth response code: ${response.code} from $endpoint")
        Log.d("ScrollSentryAPI", "Auth response body: $responseBody")
        
        if (response.code == 202) {
            // Special code for new user needing username
            throw UserNeedsUsernameException(JSONObject(responseBody))
        }
        
        if (!response.isSuccessful) {
            val error = try { JSONObject(responseBody).getString("error") } catch(e:Exception) { "HTTP ${response.code}" }
            throw IllegalStateException("Backend Error: $error (Status: ${response.code}) at $endpoint")
        }
        
        val json = JSONObject(responseBody)
        return BackendUser(
            username = json.getString("username"),
            googleUid = json.optString("googleUid"),
            email = json.optString("email"),
            displayName = json.optString("displayName"),
            photoUrl = json.optString("photoUrl")
        )
    }

    class UserNeedsUsernameException(val details: JSONObject) : Exception("Username required")

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
            status = json.getString("status"),
            type = json.optString("type", "EXTENSION_REQUEST"),
            reason = json.optString("reason").takeIf { it.isNotBlank() },
            createdAt = json.optLong("createdAt").takeIf { it > 0L }
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
                minutes = obj.optInt("minutes", 0),
                status = obj.getString("status"),
                type = obj.optString("type", "EXTENSION_REQUEST"),
                reason = obj.optString("reason").takeIf { it.isNotBlank() },
                createdAt = obj.optLong("createdAt").takeIf { it > 0L }
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
            minutes = json.optInt("minutes", 0),
            status = json.getString("status"),
            type = json.optString("type", "EXTENSION_REQUEST"),
            reason = json.optString("reason").takeIf { it.isNotBlank() },
            createdAt = json.optLong("createdAt").takeIf { it > 0L }
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
            minutes = json.optInt("minutes", 0),
            status = json.getString("status"),
            type = json.optString("type", "EXTENSION_REQUEST"),
            reason = json.optString("reason").takeIf { it.isNotBlank() },
            createdAt = json.optLong("createdAt").takeIf { it > 0L }
        )
    }

    fun reportProtectionLoss(username: String, reason: String, timestamp: Long, friends: List<String>) {
        val body = JSONObject()
            .put("username", username)
            .put("reason", reason)
            .put("timestamp", timestamp)
            .put("friends", JSONArray(friends))
            .toString()
        val request = Request.Builder()
            .url("$baseUrl/api/protection-events")
            .post(body.toRequestBody(jsonType))
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IllegalStateException("Protection event report failed")
        }
    }
}
