package com.interpretertrainer.app.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterpreterDeveloperProfileTest {
    @Test
    fun profileContainsOnlyVerifiedAcademicIdentity() {
        val profile = InterpreterDeveloperProfile.aiContext

        assertTrue(profile.contains("Zouhair Elachaqi"))
        assertTrue(profile.contains("Université Mohammed V"))
        assertTrue(profile.contains("Licence de l’Éducation"))
        assertTrue(profile.contains("Interactive Learning Applications"))
        assertFalse(profile.contains("Master", ignoreCase = true))
    }
}
