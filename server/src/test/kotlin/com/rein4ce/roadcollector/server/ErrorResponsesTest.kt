package com.rein4ce.roadcollector.server

import com.rein4ce.roadcollector.core.model.TowerMode
import com.rein4ce.roadcollector.core.model.TowerSummary
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.Test

/** 思わぬ例外のときに、中の様子（例外の文言）を応答に出さないこと（ErrorResponses.kt） */
class ErrorResponsesTest {

    /** 一覧を読もうとすると、権限が足りないときの GCP のような例外を投げる保存先 */
    private class FailingStore : TowerStore by MemoryTowerStore() {
        override suspend fun summaries(owner: String?, mode: TowerMode): List<TowerSummary> =
            throw IllegalStateException("PERMISSION_DENIED: Missing or insufficient permissions.")
    }

    @Test
    fun `思わぬ例外では 500 を返し、例外の文言は返さない`() = testApplication {
        application { module(store = FailingStore(), namer = DisabledPartNamer) }

        val res = client.get("/towers")
        assertEquals(HttpStatusCode.InternalServerError, res.status)
        assertFalse(res.bodyAsText().contains("PERMISSION_DENIED"), res.bodyAsText())
    }
}
