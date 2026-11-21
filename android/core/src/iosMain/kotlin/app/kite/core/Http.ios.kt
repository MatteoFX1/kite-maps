package app.kite.core

import io.ktor.client.engine.*
import io.ktor.client.engine.darwin.*

internal actual fun httpEngine(): HttpClientEngineFactory<*> = Darwin
