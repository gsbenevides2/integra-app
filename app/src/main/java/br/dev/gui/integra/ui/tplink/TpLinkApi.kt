package br.dev.gui.integra.ui.tplink

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

// Modelos de server/modules/tplink (model.ts + services).

@Immutable
@Serializable
data class TpInterface(
    val id: String,
    val name: String = "",
    val mac: String = "",
    val ip: String = "",
    val deviceId: String = "",
    val reservedIp: Boolean = false,
    val allowList: Boolean = false,
)

@Immutable
@Serializable
data class TpDevice(
    val id: String,
    val name: String = "",
    val brand: String = "",
    /** "router" | "client" */
    val type: String = "client",
    val isController: Boolean = false,
    val interfaces: List<TpInterface> = emptyList(),
) {
    val isRouter get() = type == "router"
}

@Immutable
@Serializable
data class RouterStatus(
    val wanIp: String = "",
    val connectionStatus: String = "",
    val connectionUptime: String = "",
    val routerUptime: String = "",
    val firmwareVersion: String = "",
    val hardwareVersion: String = "",
    val cpuUsage: Double? = null,
    val memoryUsage: Double? = null,
    val totalDownload: String? = null,
    val totalUpload: String? = null,
)

@Immutable
@Serializable
data class RouterStatusHistoryPoint(
    val id: String = "",
    val cpuUsage: Double? = null,
    val memoryUsage: Double? = null,
    val connectionStatus: String = "",
    /** ISO 8601 */
    val collectedAt: String = "",
)

@Serializable
data class RouterStatusHistory(val snapshots: List<RouterStatusHistoryPoint>? = null)

@Immutable
@Serializable
data class OnlineDevice(
    val mac: String = "",
    val ip: String = "",
    val vendor: String = "",
    val name: String = "",
    val routerInterface: String = "",
)

@Serializable
data class LatestCheck(val devices: List<OnlineDevice>? = null)

@Immutable
@Serializable
data class DeviceHistoryEntry(val checkId: String = "", val createdAt: Long = 0, val online: Boolean = false)

@Serializable
data class NewDeviceBody(
    val name: String,
    val brand: String,
    val type: String,
    // Nulos são omitidos do JSON (explicitNulls = false), como o `undefined` do web.
    val isController: Boolean? = null,
    val routerPassword: String? = null,
)

@Serializable
data class NewInterfaceBody(val name: String, val mac: String, val ip: String)

@Serializable
data class InterfaceFlagsBody(val reservedIp: Boolean? = null, val allowList: Boolean? = null)

@Serializable
data class CreatedId(val id: String)

interface TpLinkApi {
    @GET("api/tplink/devices")
    suspend fun devices(): List<TpDevice>

    @POST("api/tplink/devices")
    suspend fun createDevice(@Body body: NewDeviceBody): CreatedId

    @DELETE("api/tplink/devices/{id}")
    suspend fun deleteDevice(@Path("id") id: String)

    @POST("api/tplink/devices/{id}/interface")
    suspend fun createInterface(@Path("id") deviceId: String, @Body body: NewInterfaceBody)

    @PUT("api/tplink/devices/{id}/interface/{interfaceId}")
    suspend fun updateInterface(
        @Path("id") deviceId: String,
        @Path("interfaceId") interfaceId: String,
        @Body body: InterfaceFlagsBody,
    )

    @DELETE("api/tplink/devices/{id}/interface/{interfaceId}")
    suspend fun deleteInterface(@Path("id") deviceId: String, @Path("interfaceId") interfaceId: String)

    @GET("api/tplink/devices/{id}/history")
    suspend fun history(@Path("id") deviceId: String, @Query("from") from: String, @Query("to") to: String): List<DeviceHistoryEntry>

    @POST("api/tplink/router/sync")
    suspend fun sync()

    @POST("api/tplink/router/restart-network")
    suspend fun restartNetwork()

    @GET("api/tplink/checks/latest")
    suspend fun latestCheck(): LatestCheck

    @GET("api/tplink/settings/latest-router-status")
    suspend fun latestRouterStatus(): RouterStatus

    @GET("api/tplink/settings/router-status-history")
    suspend fun routerStatusHistory(): RouterStatusHistory
}
