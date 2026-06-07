package com.example.paperclipper

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

/**
 * End-to-end smoke test: launches the real MainActivity and confirms the home UI wires up
 * (setContent -> ClipperApp -> HomeScreen). On a fresh install with no clippings the take-photo
 * button is always present.
 */
class AppLaunchSmokeTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appLaunches_showsHomeControls() {
        rule.waitForIdle()
        rule.onNodeWithText("Take a photo").assertIsDisplayed()
    }
}
