package com.masterllm.testing.robot

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.onNodeWithText

class ChatRobot(private val test: ComposeUiTest) {
    fun assertTitleVisible(title: String): ChatRobot {
        test.onNodeWithText(title).assertExists()
        return this
    }
}
