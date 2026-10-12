package br.dev.gui.integra.ui.crons

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/** `server/modules/crons`. */
interface CronsApi {
    @GET("api/crons/list")
    suspend fun list(): CronListResponse

    @POST("api/crons/run/{jobName}")
    suspend fun run(@Path("jobName") jobName: String): CronRunResponse
}

@Immutable
@Serializable
data class CronJob(
    val name: String,
    val label: String = "",
    val schedule: String = "",
    val scheduleLabel: String = "",
)

@Serializable
data class CronListResponse(val ok: Boolean = false, val jobs: List<CronJob> = emptyList())

@Serializable
data class CronRunResponse(
    val ok: Boolean = false,
    val job: String? = null,
    val elapsed: Long? = null,
    val error: String? = null,
)
