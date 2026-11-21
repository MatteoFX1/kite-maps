package app.kite.core

import io.ktor.client.engine.*
import io.ktor.client.engine.okhttp.*

internal actual fun httpEngine(): HttpClientEngineFactory<*> = OkHttp
