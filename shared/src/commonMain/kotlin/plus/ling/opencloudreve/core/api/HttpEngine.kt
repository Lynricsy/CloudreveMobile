package plus.ling.opencloudreve.core.api

import io.ktor.client.engine.HttpClientEngine

/** 平台默认 HTTP 引擎：Android 为 OkHttp，iOS 为 Darwin(NSURLSession) */
expect fun defaultHttpEngine(): HttpClientEngine
