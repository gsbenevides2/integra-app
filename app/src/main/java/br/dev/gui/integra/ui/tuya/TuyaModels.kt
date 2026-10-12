package br.dev.gui.integra.ui.tuya

import androidx.compose.runtime.Immutable
import br.dev.gui.integra.data.Integra
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

// Tipos espelhados de public/dashboards/tuya/types.ts e server/modules/tuya/model.ts.
// Todos @Immutable: o polling de 5 s troca as listas, e cards com dados iguais pulam a recomposição.

const val WORK_MODE_WHITE = "white"
const val WORK_MODE_COLOUR = "colour"

@Immutable
@Serializable
data class DeviceState(
    val online: Boolean = false,
    val power: Boolean? = null,
    /** 0-100, normalizado pelo servidor. */
    val brightness: Int? = null,
    /** 0-100, normalizado pelo servidor. */
    val colorTemp: Int? = null,
    val colorHex: String? = null,
    /** "white" | "colour" | "scene" | "music" */
    val workMode: String? = null,
    /** Só interruptores: estado de cada relé, pelo número do canal. */
    val channels: Map<String, Boolean>? = null,
)

@Immutable
@Serializable
data class Device(
    val id: String,
    val name: String,
    val tuyaDeviceId: String = "",
    /** "lamp" | "switch" */
    val kind: String = "lamp",
    val channelCount: Int? = null,
    val enabled: Boolean = true,
    val hidden: Boolean = false,
    val lastSeenAt: String? = null,
    val createdAt: String? = null,
    val state: DeviceState = DeviceState(),
) {
    val isSwitch get() = kind == "switch"
    val isLamp get() = kind == "lamp"
}

@Immutable
@Serializable
data class HistoryPoint(
    val id: String = "",
    val online: Boolean = false,
    val power: Boolean? = null,
    val brightness: Int? = null,
    val colorTemp: Int? = null,
    val colorHex: String? = null,
    val workMode: String? = null,
    val recordedAt: String,
)

@Serializable
data class DeviceHistory(val snapshots: List<HistoryPoint> = emptyList())

/** Corpo de `POST /api/tuya/devices/:id/command`. Campos nulos não são enviados. */
@Immutable
@Serializable
data class DeviceCommand(
    val power: Boolean? = null,
    val brightness: Int? = null,
    val colorTemp: Int? = null,
    val colorHex: String? = null,
    val workMode: String? = null,
    val channels: Map<String, Boolean>? = null,
)

@Immutable
@Serializable
data class Preset(
    val id: String,
    val name: String,
    val power: Boolean = true,
    val brightness: Int? = null,
    val colorTemp: Int? = null,
    val colorHex: String? = null,
    val workMode: String? = null,
    val createdAt: String? = null,
)

@Immutable
@Serializable
data class Sensor(
    val id: String,
    val name: String,
    val tuyaDeviceId: String = "",
    /** "temperature_humidity" | "door" | "motion" | "unknown" */
    val kind: String = "unknown",
    val category: String? = null,
    val online: Boolean = false,
    val enabled: Boolean = true,
    val hidden: Boolean = false,
    val lastEventAt: String? = null,
    val lastSeenAt: String? = null,
    val readings: Map<String, String> = emptyMap(),
)

@Immutable
@Serializable
data class SensorReading(
    val id: String = "",
    val code: String,
    val value: String,
    val recordedAt: String,
)

@Serializable
data class SensorHistory(val readings: List<SensorReading> = emptyList())

@Serializable
data class NewDeviceBody(
    val name: String,
    val tuyaDeviceId: String,
    val kind: String,
    val channelCount: Int? = null,
)

@Serializable
data class NewSensorBody(val name: String, val tuyaDeviceId: String)

/** `PUT /api/tuya/devices/:id` parcial: só os campos não nulos vão no corpo. */
@Serializable
data class DevicePatch(val name: String? = null, val hidden: Boolean? = null)

/** `PUT /api/tuya/sensors/:id` parcial. */
@Serializable
data class SensorPatch(val name: String? = null, val enabled: Boolean? = null, val hidden: Boolean? = null)

@Serializable
data class ApplyPresetBody(val deviceId: String)

/** Rotas de server/modules/tuya/index.ts e server/modules/frigate. */
interface TuyaApi {
    @GET("api/tuya/devices")
    suspend fun devices(@Query("includeHidden") includeHidden: String = "true"): List<Device>

    @POST("api/tuya/devices")
    suspend fun createDevice(@Body body: NewDeviceBody)

    @PUT("api/tuya/devices/{id}")
    suspend fun updateDevice(@Path("id") id: String, @Body body: DevicePatch)

    @DELETE("api/tuya/devices/{id}")
    suspend fun deleteDevice(@Path("id") id: String)

    @POST("api/tuya/devices/{id}/command")
    suspend fun command(@Path("id") id: String, @Body body: DeviceCommand): DeviceState

    @GET("api/tuya/devices/{id}/history")
    suspend fun deviceHistory(@Path("id") id: String): DeviceHistory

    @GET("api/tuya/sensors")
    suspend fun sensors(@Query("includeHidden") includeHidden: String = "true"): List<Sensor>

    @POST("api/tuya/sensors")
    suspend fun createSensor(@Body body: NewSensorBody)

    @PUT("api/tuya/sensors/{id}")
    suspend fun updateSensor(@Path("id") id: String, @Body body: SensorPatch)

    @GET("api/tuya/sensors/{id}/history")
    suspend fun sensorHistory(@Path("id") id: String, @Query("code") code: String): SensorHistory

    @GET("api/tuya/presets")
    suspend fun presets(): List<Preset>

    /** Corpo em JsonObject para mandar `null` explícito (o PUT parcial ignora campos ausentes). */
    @POST("api/tuya/presets")
    suspend fun createPreset(@Body body: JsonObject)

    @PUT("api/tuya/presets/{id}")
    suspend fun updatePreset(@Path("id") id: String, @Body body: JsonObject)

    @DELETE("api/tuya/presets/{id}")
    suspend fun deletePreset(@Path("id") id: String)

    @POST("api/tuya/presets/{id}/apply")
    suspend fun applyPreset(@Path("id") id: String, @Body body: ApplyPresetBody)

    @GET("api/frigate/cameras")
    suspend fun cameras(): List<String>
}

internal val tuyaApi: TuyaApi by lazy { Integra.api<TuyaApi>() }
