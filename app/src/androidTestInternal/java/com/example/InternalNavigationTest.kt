package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real local profile UI; Google sign-in and the external camera are separate device checks. */
@RunWith(AndroidJUnit4::class)
class InternalNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun localEntry_signOutReentryAndProfileDeletion_returnToLogin() {
        compose.onNodeWithTag("demo_login_button").performScrollTo().performClick()
        waitFor("dashboard_screen")
        compose.onNodeWithTag("logout_button").performClick()
        waitFor("login_screen")
        compose.onNodeWithTag("demo_login_button").performScrollTo().performClick()
        waitFor("dashboard_screen")
        compose.onNodeWithTag("nav_settings_tab").performClick()
        waitFor("settings_screen")
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("settings_delete_account_button"))
        compose.onNodeWithTag("settings_delete_account_button").performClick()
        compose.onNodeWithTag("confirm_delete_account_button").performClick()
        waitFor("login_screen")
        compose.onNodeWithTag("demo_login_button").performScrollTo().assertIsDisplayed()
    }

    private fun waitFor(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }
}
