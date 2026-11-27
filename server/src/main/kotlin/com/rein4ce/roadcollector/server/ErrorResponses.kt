package com.rein4ce.roadcollector.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.CallFailed
import io.ktor.server.engine.defaultExceptionStatusCode
import io.ktor.server.engine.logError
import io.ktor.server.response.respond
import kotlinx.coroutines.CancellationException

/**
 * 思わぬ例外で失敗した呼び出しに、例外の文言を返さない（ログにだけ残す）。
 *
 * Ktor の既定は、例外の文言をそのまま応答の本文に書く。そのため、権限が足りないときの GCP のエラー
 * （`io.grpc.StatusRuntimeException: PERMISSION_DENIED: …`）のような、サーバの中の様子が分かる文が外に出ていた。
 * 状態コードは Ktor の既定と同じ（BadRequestException なら 400、多くは 500）。ログも Ktor の既定と同じ書き方で残す。
 * 接続が切れたとき（CancellationException）と、もう応答を書き始めていたときは、Ktor の既定に任せる
 */
val HideErrorDetails = createApplicationPlugin("HideErrorDetails") {
    on(CallFailed) { call, cause ->
        if (cause is CancellationException || call.response.isCommitted) return@on
        logError(call, cause)
        call.respond(defaultExceptionStatusCode(cause) ?: HttpStatusCode.InternalServerError)
    }
}
