package br.dev.gui.integra

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import kotlin.math.sqrt

/**
 * Detecta "duas batidas" no aparelho usando o acelerômetro.
 *
 * Com a tela desligada o touchscreen fica desligado, então não dá para ler toques nele
 * sem suporte do fabricante. Em vez disso olhamos picos curtos de aceleração causados
 * pelas batidas do dedo na tela. Movimentos contínuos (pegar o celular, andar) geram
 * muitos picos seguidos ou não têm silêncio antes, e são descartados.
 */
class KnockDetector(
    var threshold: Float,
    private val onDoubleKnock: () -> Unit,
    private val onSample: ((magnitude: Float) -> Unit)? = null,
) : SensorEventListener {

    private val gravity = FloatArray(3)
    private var initialized = false

    private var aboveThreshold = false
    private val peaks = ArrayList<Long>()
    private var sequenceValid = true

    // Rastreamento de "atividade" (movimento acima de uma fração do limiar).
    private var lastActivityNs = 0L
    private var burstStartNs = 0L
    private var prevBurstEndNs = 0L

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val v = event.values
        val now = event.timestamp
        if (!initialized) {
            v.copyInto(gravity, 0, 0, 3)
            initialized = true
            return
        }
        // Filtro passa-alta simples: remove a gravidade para obter só a aceleração brusca.
        val alpha = 0.9f
        for (i in 0..2) gravity[i] = alpha * gravity[i] + (1 - alpha) * v[i]
        val x = v[0] - gravity[0]
        val y = v[1] - gravity[1]
        val z = v[2] - gravity[2]
        val mag = sqrt(x * x + y * y + z * z)
        onSample?.invoke(mag)

        evaluate(now)

        if (mag > threshold * ACTIVITY_FRACTION) {
            if (now - lastActivityNs > BURST_GAP_NS) {
                prevBurstEndNs = lastActivityNs
                burstStartNs = now
            }
            lastActivityNs = now
        }

        if (mag > threshold) {
            if (!aboveThreshold && (peaks.isEmpty() || now - peaks.last() > MIN_PEAK_GAP_NS)) {
                if (peaks.isEmpty()) {
                    // A 1ª batida precisa vir depois de um período parado.
                    sequenceValid = prevBurstEndNs == 0L || burstStartNs - prevBurstEndNs > PRE_QUIET_NS
                }
                peaks.add(now)
            }
            aboveThreshold = true
        } else if (mag < threshold * 0.6f) {
            aboveThreshold = false
        }
    }

    private fun evaluate(now: Long) {
        if (peaks.isEmpty()) return
        if (now - peaks.last() < SETTLE_NS) return
        // A sequência terminou: só dispara se foram exatamente duas batidas.
        if (sequenceValid && peaks.size == 2) {
            val gap = peaks[1] - peaks[0]
            if (gap in MIN_GAP_NS..MAX_GAP_NS) onDoubleKnock()
        }
        peaks.clear()
    }

    fun reset() {
        peaks.clear()
        initialized = false
        aboveThreshold = false
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val MS = 1_000_000L
        private const val ACTIVITY_FRACTION = 0.35f
        private const val BURST_GAP_NS = 60 * MS      // amostras ativas mais próximas que isso = mesmo movimento
        private const val MIN_PEAK_GAP_NS = 70 * MS   // ignora o "eco" da mesma batida
        private const val MIN_GAP_NS = 90 * MS        // intervalo mínimo entre as 2 batidas
        private const val MAX_GAP_NS = 650 * MS       // intervalo máximo entre as 2 batidas
        private const val SETTLE_NS = 450 * MS        // espera para garantir que não houve 3ª batida
        private const val PRE_QUIET_NS = 700 * MS     // silêncio exigido antes da 1ª batida
    }
}
