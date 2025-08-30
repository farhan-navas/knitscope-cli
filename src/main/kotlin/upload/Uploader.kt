package upload

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.time.Duration

class Uploader(private val baseUrl: String) {
    private val client = OkHttpClient.Builder()
        .callTimeout(Duration.ofSeconds(20))
        .build()

    fun upload(json: String): String {
        val req = Request.Builder()
            .url("$baseUrl/api/graphs")
            .post(RequestBody.create("application/json".toMediaType(), json))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Upload failed: ${resp.code}")
            val id = resp.body?.string()?.trim().orEmpty()
            if (id.isEmpty()) error("Empty server response")
            return id // server returns an id; front-end can open /graph/<id>
        }
    }
}
