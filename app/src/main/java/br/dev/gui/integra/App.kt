package br.dev.gui.integra

import android.app.Application
import br.dev.gui.integra.data.Integra
import br.dev.gui.integra.data.Telemetry
import br.dev.gui.integra.voice.Assistant
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Integra.init(this)
        // O quanto antes, para o RUM pegar a abertura do app e eventuais crashes.
        Telemetry.init(this)
        Assistant.init(this)
        // Imagens (Coil) pelo mesmo OkHttp: Basic Auth no integra, nada na URL pré-assinada do S3.
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context).components { add(OkHttpNetworkFetcherFactory(callFactory = { Integra.http })) }.build()
        }
    }
}
