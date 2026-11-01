package com.mivimoose.core

import com.mivimoose.core.net.GemRate
import org.junit.Assert.assertEquals
import org.junit.Test

class GemRateTest {
    @Test
    fun gradeLabels() {
        assertEquals("10", GemRate.label("psa", "psa_10"))
        assertEquals("9.5", GemRate.label("beckett", "beckett_9_5"))
        assertEquals("10 Pristine", GemRate.label("cgc", "cgc_10_pristine"))
        assertEquals("10 Black", GemRate.label("beckett", "beckett_10_black"))
        assertEquals("8 Q", GemRate.label("psa", "psa_q8"))
        assertEquals("1.5 Q", GemRate.label("psa", "psa_q1_5"))
        assertEquals("Auth", GemRate.label("sgc", "sgc_auth"))
        assertEquals("Auth Altered", GemRate.label("cgc", "cgc_auth_altered"))
    }
}
