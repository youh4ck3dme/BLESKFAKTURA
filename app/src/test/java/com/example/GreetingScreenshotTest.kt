package com.example

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class GreetingScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test(timeout = 90_000L)
  fun brand_header_renders_in_theme() {
    composeTestRule.setContent {
      MyApplicationTheme {
        Surface {
          Text(
            text = "Blesková Faktúra",
            style = MaterialTheme.typography.headlineMedium
          )
        }
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Blesková Faktúra").assertIsDisplayed()
    val screenshot = File("build/reports/screenshots/${BuildConfig.FLAVOR}/brand_header.png")
    check(screenshot.parentFile.mkdirs() || screenshot.parentFile.isDirectory)
    composeTestRule.onRoot().captureRoboImage(filePath = screenshot.path)
    assertTrue("Native screenshot was not written", screenshot.isFile && screenshot.length() > 0L)
  }
}
