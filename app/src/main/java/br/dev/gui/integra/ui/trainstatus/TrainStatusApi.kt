package br.dev.gui.integra.ui.trainstatus

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import br.dev.gui.integra.ui.theme.Tw
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/** `server/modules/train-status`. */
interface TrainStatusApi {
    @GET("api/train-status/lines")
    suspend fun lines(): List<TrainLineRow>

    @GET("api/train-status/{code}/history")
    suspend fun history(@Path("code") code: Int, @Query("before") before: String? = null): TrainHistory
}

@Immutable
@Serializable
data class TrainLineRow(
    val lineCode: Int,
    val lineColor: String = "",
    val situation: String = "",
    val status: String? = null,
    val description: String? = null,
    val checkedAt: String? = null,
)

@Immutable
@Serializable
data class TrainStatusCheck(
    val status: String,
    val situation: String = "",
    val description: String? = null,
    val checkedAt: String,
)

@Immutable
@Serializable
data class TrainStatusSegment(
    val status: String,
    val situation: String = "",
    val startedAt: String,
    val endedAt: String? = null,
    val description: String? = null,
)

@Immutable
@Serializable
data class TrainHistory(
    val checks: List<TrainStatusCheck> = emptyList(),
    val segments: List<TrainStatusSegment> = emptyList(),
    override val hasMore: Boolean = false,
    override val nextCursor: String? = null,
) : HistoryPage

/** `TrainLineStatus` com as cores e rótulos usados no Card e no HistoryModal. */
enum class TrainLineStatus(val value: Int, val label: String, val dot: Color, val text: Color) {
    CRITICAL(0, "Critical", Tw.red700, Tw.red400),
    UNKNOWN(1, "Unknown", Tw.gray600, Tw.gray400),
    WARNING(2, "Warning", Tw.yellow600, Tw.yellow400),
    OK(3, "Operational", Tw.green700, Tw.green400);

    companion object {
        fun of(value: String?): TrainLineStatus? = entries.firstOrNull { it.name == value }
    }
}

@Immutable
data class TrainLineData(val code: Int, val color: String, val company: String, val cssColor: Color)

/** Cópia de `TRAIN_LINES` (server/modules/train-status/model.ts). */
val TRAIN_LINES = listOf(
    TrainLineData(1, "Azul", "metro", Color(0xFF0066CC)),
    TrainLineData(2, "Verde", "metro", Color(0xFF00AA33)),
    TrainLineData(3, "Vermelha", "metro", Color(0xFFCC0000)),
    TrainLineData(4, "Amarela", "ccr", Color(0xFFFFDD00)),
    TrainLineData(5, "Lilás", "ccr", Color(0xFF9933CC)),
    TrainLineData(6, "Laranja", "liauni", Color(0xFFFF9900)),
    TrainLineData(7, "Rubi", "tic", Color(0xFFC41E3A)),
    TrainLineData(8, "Diamante", "ccr", Color(0xFFE8E8E8)),
    TrainLineData(9, "Esmeralda", "ccr", Color(0xFF00A86B)),
    TrainLineData(10, "Turquesa", "cptm", Color(0xFF40E0D0)),
    TrainLineData(11, "Coral", "cptm", Color(0xFFFF7F50)),
    TrainLineData(12, "Safira", "cptm", Color(0xFF0F52BA)),
    TrainLineData(13, "Jade", "cptm", Color(0xFF00A36C)),
    TrainLineData(15, "Prata", "metro", Color(0xFFC0C0C0)),
    TrainLineData(17, "Ouro", "metro", Color(0xFFFFD700)),
)
