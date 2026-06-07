package com.example.paperclipper

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FilterDialogUiTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun showsBothSortOptions() {
        rule.setContent { MaterialTheme { FilterDialog(sortDescending = true, onApply = {}, onDismiss = {}) } }
        rule.onNodeWithText("Date — newest first").assertExists()
        rule.onNodeWithText("Date — oldest first").assertExists()
    }

    @Test
    fun selectingOldestThenApply_reportsAscending() {
        var applied: Boolean? = null
        rule.setContent {
            MaterialTheme { FilterDialog(sortDescending = true, onApply = { applied = it }, onDismiss = {}) }
        }
        rule.onNodeWithText("Date — oldest first").performClick()
        rule.onNodeWithText("Apply").performClick()
        assertEquals(false, applied)
    }

    @Test
    fun defaultApply_reportsDescending() {
        var applied: Boolean? = null
        rule.setContent {
            MaterialTheme { FilterDialog(sortDescending = true, onApply = { applied = it }, onDismiss = {}) }
        }
        rule.onNodeWithText("Apply").performClick()
        assertEquals(true, applied)
    }

    @Test
    fun cancel_invokesDismiss() {
        var dismissed = false
        rule.setContent {
            MaterialTheme { FilterDialog(sortDescending = true, onApply = {}, onDismiss = { dismissed = true }) }
        }
        rule.onNodeWithText("Cancel").performClick()
        assertTrue(dismissed)
    }
}
