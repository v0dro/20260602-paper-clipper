package com.example.paperclipper

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.example.paperclipper.data.Clipping
import com.example.paperclipper.data.ClippingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * UI behavior tests for HomeScreen. The composable takes plain data + callbacks (no ViewModel),
 * so these are deterministic. Runs only on a device/emulator via connectedAndroidTest.
 */
class HomeScreenUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun clip(name: String, summary: String?, status: ClippingStatus = ClippingStatus.SUCCESS) =
        Clipping(File(name), 1_000L, status, extractedText = null, summary = summary, heading = null, errorMessage = null)

    private fun setHome(
        clippings: List<Clipping>,
        onTakePhoto: () -> Unit = {},
        onOpen: (Clipping) -> Unit = {},
        onDelete: (List<File>) -> Unit = {},
        onExport: (android.net.Uri) -> Unit = {},
        onSendFeedback: (String) -> Unit = {},
        onClearAll: () -> Unit = {},
    ) {
        rule.setContent {
            MaterialTheme {
                HomeScreen(
                    clippings = clippings,
                    userEmail = null,
                    userName = null,
                    onTakePhoto = onTakePhoto,
                    onOpen = onOpen,
                    onDelete = onDelete,
                    onExport = onExport,
                    signInIntentProvider = { null },
                    onSignInResult = {},
                    onSignOut = {},
                    onClearAll = onClearAll,
                    onSendFeedback = onSendFeedback,
                )
            }
        }
    }

    @Test
    fun emptyLibrary_showsEmptyState() {
        setHome(emptyList())
        rule.onNodeWithText("No clippings yet — tap Take a photo below.").assertIsDisplayed()
        rule.onNodeWithText("Take a photo").assertIsDisplayed()
    }

    @Test
    fun nonEmptyLibrary_showsSummaries() {
        setHome(listOf(clip("a.jpg", "Alpha summary"), clip("b.jpg", "Beta summary")))
        rule.onNodeWithText("Alpha summary").assertIsDisplayed()
        rule.onNodeWithText("Beta summary").assertIsDisplayed()
    }

    @Test
    fun search_filtersOutNonMatchingClippings() {
        setHome(listOf(clip("a.jpg", "Apple pie"), clip("b.jpg", "Banana bread")))
        rule.onNodeWithText("Search clippings").performTextInput("Apple")

        rule.onNodeWithText("Apple pie", substring = true).assertIsDisplayed()
        rule.onAllNodesWithContentDescription("Saved clipping").fetchSemanticsNodes().let {
            assertEquals(1, it.size)
        }
    }

    @Test
    fun takePhoto_invokesCallback() {
        var taken = false
        setHome(emptyList(), onTakePhoto = { taken = true })
        rule.onNodeWithText("Take a photo").performClick()
        assertTrue(taken)
    }

    @Test
    fun drawer_showsActionsAndFeedbackFlowSends() {
        var sent: String? = null
        setHome(emptyList(), onSendFeedback = { sent = it })

        rule.onNodeWithContentDescription("Open menu").performClick()
        rule.onNodeWithText("Export").assertIsDisplayed()
        rule.onNodeWithText("Clear all").assertIsDisplayed()
        rule.onNodeWithText("Log in").assertIsDisplayed()

        rule.onNodeWithText("Give feedback").performClick()
        rule.onNodeWithText("What's working, what's broken, ideas…").performTextInput("nice app")
        rule.onNodeWithText("Send").performClick()
        assertEquals("nice app", sent)
    }

    @Test
    fun clearAll_confirmInvokesCallback() {
        var cleared = false
        setHome(emptyList(), onClearAll = { cleared = true })

        rule.onNodeWithContentDescription("Open menu").performClick()
        rule.onNodeWithText("Clear all").performClick()
        rule.onNodeWithText("Delete everything").performClick()
        assertTrue(cleared)
    }

    @Test
    fun longPress_entersSelectionAndDeletes() {
        var deleted: List<File>? = null
        setHome(listOf(clip("a.jpg", "Alpha summary")), onDelete = { deleted = it })

        rule.onNodeWithContentDescription("Saved clipping").performTouchInput { longClick() }
        rule.onNodeWithText("1 selected").assertIsDisplayed()
        rule.onNodeWithContentDescription("Delete selected").performClick()

        assertEquals(listOf(File("a.jpg")), deleted)
    }
}
