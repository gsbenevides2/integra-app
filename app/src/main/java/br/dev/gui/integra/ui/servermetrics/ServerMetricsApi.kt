package br.dev.gui.integra.ui.servermetrics

import androidx.compose.runtime.Immutable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.http.GET

/** Aceita número ou string numérica ("12.5"); qualquer outra coisa vira 0. */
object FlexibleDoubleSerializer : KSerializer<Double> {
    override val descriptor = PrimitiveSerialDescriptor("FlexibleDouble", PrimitiveKind.DOUBLE)

    override fun deserialize(decoder: Decoder): Double {
        if (decoder !is JsonDecoder) return decoder.decodeDouble()
        val element = decoder.decodeJsonElement()
        return (element as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
    }

    override fun serialize(encoder: Encoder, value: Double) = encoder.encodeDouble(value)
}

typealias FlexibleDouble = @Serializable(with = FlexibleDoubleSerializer::class) Double

/**
 * `server/modules/server-metrics`. Os números podem vir como string do banco (numeric do Postgres),
 * por isso os campos usam [FlexibleDouble].
 */
interface ServerMetricsApi {
    @GET("api/server-metrics/history")
    suspend fun history(): SnapshotHistory

    @GET("api/server-metrics/speedtest/history")
    suspend fun speedtestHistory(): SpeedtestHistory
}

@Immutable
@Serializable
data class DiskSnapshot(
    val filesystem: String = "",
    val totalMb: FlexibleDouble = 0.0,
    val usedMb: FlexibleDouble = 0.0,
    val freeMb: FlexibleDouble = 0.0,
    val usagePercent: FlexibleDouble = 0.0,
    val mountedAt: String = "",
)

@Immutable
@Serializable
data class Snapshot(
    val id: String,
    val memoryTotalMb: FlexibleDouble = 0.0,
    val memoryUsedMb: FlexibleDouble = 0.0,
    val memoryFreeMb: FlexibleDouble = 0.0,
    val networkRxKbs: FlexibleDouble = 0.0,
    val networkTxKbs: FlexibleDouble = 0.0,
    val disks: List<DiskSnapshot> = emptyList(),
    val collectedAt: String,
)

@Immutable
@Serializable
data class SpeedtestSnapshot(
    val id: String,
    val downloadMbps: FlexibleDouble = 0.0,
    val uploadMbps: FlexibleDouble = 0.0,
    val latencyMs: FlexibleDouble = 0.0,
    val collectedAt: String,
)

@Serializable
data class SnapshotHistory(
    val snapshots: List<Snapshot> = emptyList(),
    val hasMore: Boolean = false,
    val nextCursor: String? = null,
)

@Serializable
data class SpeedtestHistory(
    val snapshots: List<SpeedtestSnapshot> = emptyList(),
    val hasMore: Boolean = false,
    val nextCursor: String? = null,
)
