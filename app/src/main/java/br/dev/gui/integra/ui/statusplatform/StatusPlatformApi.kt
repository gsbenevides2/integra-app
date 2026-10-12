package br.dev.gui.integra.ui.statusplatform

import androidx.compose.runtime.Immutable
import br.dev.gui.integra.ui.trainstatus.HistoryPage
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** `server/modules/status-platform`. */
interface StatusPlatformApi {
    @GET("api/status-platform/list")
    suspend fun list(): List<PlatformRow>

    @GET("api/status-platform/{id}/history")
    suspend fun history(@Path("id") id: String, @Query("before") before: String? = null): PlatformHistory

    @POST("api/status-platform/")
    suspend fun create(@Body body: PlatformBody)

    @PATCH("api/status-platform/{id}")
    suspend fun update(@Path("id") id: String, @Body body: PlatformBody)

    @DELETE("api/status-platform/{id}")
    suspend fun delete(@Path("id") id: String)
}

/** `PLATFORMS` (server/modules/status-platform/model.ts). */
val PLATFORMS = listOf("incident", "instatus", "atlassian", "generic", "wake", "shopify", "cloudflare")

@Immutable
@Serializable
data class PlatformRow(
    val id: String,
    val name: String = "",
    val url: String = "",
    val type: String = "",
    /** "OK" | "DOWN" | null */
    val status: String? = null,
    val problemDescription: String? = null,
    val lastCheckedAt: String? = null,
)

@Serializable
data class PlatformBody(val name: String, val url: String, val type: String)

@Immutable
@Serializable
data class PlatformStatusCheck(
    val status: String,
    val problemDescription: String? = null,
    val checkedAt: String,
)

@Immutable
@Serializable
data class PlatformStatusSegment(
    val status: String,
    val startedAt: String,
    val endedAt: String? = null,
    val problemDescription: String? = null,
)

@Immutable
@Serializable
data class PlatformHistory(
    val checks: List<PlatformStatusCheck> = emptyList(),
    val segments: List<PlatformStatusSegment> = emptyList(),
    override val hasMore: Boolean = false,
    override val nextCursor: String? = null,
) : HistoryPage
