package com.interpretertrainer.app.auth

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.interpretertrainer.app.MainActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun opensStudioDirectlyAndSurvivesRecreation() {
        compose.onNodeWithText("Sign In").assertDoesNotExist()
        compose.onNodeWithText("Continue with Facebook").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open Interpreter AI").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithContentDescription("Open Interpreter AI").assertIsDisplayed()
        compose.onNodeWithText("Sign In").assertDoesNotExist()
    }

    @Test fun conversationStorageIsEncryptedAndAccountScoped() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val suffix=System.nanoTime().toString()
        val uid="storage-a-$suffix"
        val one=AccountHistoryStore(context,uid)
        val two=AccountHistoryStore(context,"storage-b-$suffix")
        val history="[{\"role\":\"user\",\"content\":\"Private Arabic العربية\"}]"
        one.save(history)
        assertEquals(history,AccountHistoryStore(context,uid).read())
        assertEquals("[]",two.read())
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(uid.toByteArray()).joinToString(""){"%02x".format(it)}
        val stored=context.getSharedPreferences("coach_$hash",android.content.Context.MODE_PRIVATE).getString("history","")!!
        assertFalse(stored.contains("Private Arabic"))
        one.save("[]");assertEquals("[]",one.read())
    }
}
