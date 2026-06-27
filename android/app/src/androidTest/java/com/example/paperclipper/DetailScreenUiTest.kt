package com.example.paperclipper

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.example.paperclipper.data.Clipping
import com.example.paperclipper.data.ClippingStatus
import com.example.paperclipper.data.CommentEntity
import com.example.paperclipper.data.TagEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class DetailScreenUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun success() = Clipping(
        file = File("a.jpg"),
        createdAt = 1_000L,
        status = ClippingStatus.SUCCESS,
        extractedText = "the extracted text",
        summary = "the summary",
        heading = "Local News Update",
        errorMessage = null,
    )

    @Test
    fun successClipping_showsHeadingAndExtractedText() {
        rule.setContent {
            MaterialTheme {
                DetailScreen(
                    clipping = success(),
                    allTags = emptyList(),
                    assignedTagIds = emptySet(),
                    comments = emptyList(),
                    onBack = {}, onRetry = {}, onToggleTag = { _, _ -> }, onCreateTag = {},
                    onAddComment = {}, onDeleteComment = {}, onOpenImage = {},
                )
            }
        }
        // The AI heading replaces the static "Summary" label above the summary body.
        rule.onNodeWithText("Local News Update").assertIsDisplayed()
        rule.onNodeWithText("the summary").assertIsDisplayed()
        rule.onNodeWithText("Article").assertIsDisplayed()
        rule.onNodeWithText("the extracted text").assertIsDisplayed()
    }

    @Test
    fun successClipping_withoutHeading_fallsBackToSummaryLabel() {
        rule.setContent {
            MaterialTheme {
                DetailScreen(
                    clipping = success().copy(heading = null),
                    allTags = emptyList(),
                    assignedTagIds = emptySet(),
                    comments = emptyList(),
                    onBack = {}, onRetry = {}, onToggleTag = { _, _ -> }, onCreateTag = {},
                    onAddComment = {}, onDeleteComment = {}, onOpenImage = {},
                )
            }
        }
        rule.onNodeWithText("Summary").assertIsDisplayed()
        rule.onNodeWithText("the summary").assertIsDisplayed()
    }

    @Test
    fun togglingTagChip_invokesCallback() {
        var toggled: Pair<String, Boolean>? = null
        rule.setContent {
            MaterialTheme {
                DetailScreen(
                    clipping = success(),
                    allTags = listOf(TagEntity(id = 1, name = "News")),
                    assignedTagIds = emptySet(),
                    comments = emptyList(),
                    onBack = {}, onRetry = {}, onToggleTag = { tag, on -> toggled = tag.name to on },
                    onCreateTag = {}, onAddComment = {}, onDeleteComment = {}, onOpenImage = {},
                )
            }
        }
        rule.onNodeWithText("News").performClick()
        assertEquals("News" to true, toggled)
    }

    @Test
    fun addingComment_invokesCallback() {
        var added: String? = null
        rule.setContent {
            MaterialTheme {
                DetailScreen(
                    clipping = success(),
                    allTags = emptyList(),
                    assignedTagIds = emptySet(),
                    comments = emptyList(),
                    onBack = {}, onRetry = {}, onToggleTag = { _, _ -> }, onCreateTag = {},
                    onAddComment = { added = it }, onDeleteComment = {}, onOpenImage = {},
                )
            }
        }
        rule.onNodeWithText("Add a comment").performTextInput("great clipping")
        // Two "Add" buttons exist (tags + comments); the comments one is the second.
        rule.onAllNodesWithText("Add")[1].performClick()
        assertEquals("great clipping", added)
    }

    @Test
    fun errorClipping_showsRetryAndInvokesCallback() {
        var retried = false
        val errored = success().copy(
            status = ClippingStatus.ERROR,
            summary = null,
            extractedText = null,
            errorMessage = "network down",
        )
        rule.setContent {
            MaterialTheme {
                DetailScreen(
                    clipping = errored,
                    allTags = emptyList(),
                    assignedTagIds = emptySet(),
                    comments = listOf(CommentEntity(id = 1, fileName = "a.jpg", text = "note", createdAt = 1L)),
                    onBack = {}, onRetry = { retried = true }, onToggleTag = { _, _ -> }, onCreateTag = {},
                    onAddComment = {}, onDeleteComment = {}, onOpenImage = {},
                )
            }
        }
        rule.onNodeWithText("Analysis failed").assertIsDisplayed()
        rule.onNodeWithText("network down").assertIsDisplayed()
        rule.onNodeWithText("note").assertIsDisplayed()
        rule.onNodeWithText("Retry").performClick()
        assertTrue(retried)
    }
}
