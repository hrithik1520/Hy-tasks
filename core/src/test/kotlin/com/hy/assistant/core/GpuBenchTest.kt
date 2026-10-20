package com.hy.assistant.core

import com.hy.assistant.core.GpuBench.Run
import com.hy.assistant.core.GpuBench.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GpuBenchTest {
    private val answer = "The call is at 11 am on Saturday, and Priya will share the hotel options before it."

    @Test
    fun fasterGpuWithSameAnswerIsRecommended() {
        val cpu = Run(promptTokens = 400, promptMs = 4000.0, genTokens = 30, genMs = 3000.0, text = answer)
        val gpu = Run(promptTokens = 400, promptMs = 1000.0, genTokens = 30, genMs = 2500.0, text = answer)
        assertEquals(Verdict.FASTER, GpuBench.verdict(cpu, gpu))
        // 600 prompt tokens at 400/s + 60 reply tokens at 12/s
        assertEquals(1.5 + 5.0, gpu.typicalJobSec(), 1e-9)
    }

    @Test
    fun slowerOrGarbledGpuIsNot() {
        val cpu = Run(400, 2000.0, 30, 2000.0, answer)
        assertEquals(Verdict.NOT_FASTER, GpuBench.verdict(cpu, Run(400, 1900.0, 30, 2000.0, answer)))
        assertEquals(Verdict.WRONG_OUTPUT, GpuBench.verdict(cpu, Run(400, 500.0, 30, 500.0, "ÿÿ the the the ##### ok")))
        assertEquals(Verdict.WRONG_OUTPUT, GpuBench.verdict(cpu, Run(400, 500.0, 30, 500.0, "")))
    }

    @Test
    fun smallRoundingDriftStillCountsAsSame() {
        assertTrue(GpuBench.sameAnswer(answer, "The call is at 11 am on Saturday; Priya will share hotel options first."))
        assertTrue(GpuBench.sameAnswer(answer, "The call is at 11 in the morning and she shares the hotel options before it."))
        assertTrue(!GpuBench.sameAnswer(answer, "I like trains and the beach very much indeed."))
    }
}
