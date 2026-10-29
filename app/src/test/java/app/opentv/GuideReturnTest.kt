/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import app.opentv.data.model.Channel
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.channels.ChannelList
import app.opentv.ui.channels.GuideGrid
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Coming back from the live player puts the guide's highlight on the channel you were watching. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GuideReturnTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private fun channel(id: Long, name: String = "Channel $id", groupKey: String = "") = Channel(
        id = id,
        sourceId = 1,
        streamId = "$id",
        name = name,
        streamUrl = "https://example.com/$id.ts",
        categoryId = null,
        logoUrl = "https://example.com/$id.png",
        epgChannelId = null,
        number = id.toInt(),
        groupKey = groupKey,
    )

    private fun row(id: Long, variants: List<Channel> = emptyList()): ChannelsViewModel.Row {
        val primary = channel(id, groupKey = if (variants.isEmpty()) "" else "group$id")
        return ChannelsViewModel.Row(
            primary,
            listOf(primary) + variants,
            now = null,
            next = null,
            programmes = emptyList(),
        )
    }

    /** More channels than fit on a TV screen, so the guide has to scroll to reach the later ones. */
    private val rows = (1L..40L).map { id ->
        if (id == 12L) row(id, variants = listOf(channel(112, "Channel 12 HD", groupKey = "group12"))) else row(id)
    }

    private fun setGuide(channelId: Long?, takeFocus: Boolean = true) = compose.setTvContent {
        GuideGrid(
            rows = rows,
            windowStartMillis = 0,
            selectedKey = null,
            onSelectRow = {},
            onFocusRow = {},
            returnToChannelId = channelId,
            takeFocus = takeFocus,
        )
    }

    @Test
    fun `the guide comes back highlighting the channel you were watching, scrolled into view`() {
        setGuide(channelId = 30)

        compose.onNodeWithText("Channel 30").assertIsDisplayed().assertIsFocused()
        captureScreen("guide_returned_from_player")
    }

    @Test
    fun `watching a channel's other quality highlights that channel's row`() {
        setGuide(channelId = 112)

        compose.onNodeWithText("Channel 12").assertIsFocused()
    }

    @Test
    fun `a channel outside the guide's filter highlights the first channel shown`() {
        setGuide(channelId = 999)

        compose.onNodeWithText("Channel 1").assertIsFocused()
    }

    @Test
    fun `when Live is picked in the side menu, the guide scrolls to the channel but the menu keeps focus`() {
        setGuide(channelId = 30, takeFocus = false)

        compose.onNodeWithText("Channel 30").assertIsDisplayed().assertIsNotFocused()
    }

    @Test
    fun `the list layout comes back highlighting the channel too`() {
        compose.setTvContent {
            Box(Modifier.width(600.dp)) {
                ChannelList(
                    rows = rows,
                    selectedKey = null,
                    onSelectRow = {},
                    onFocusRow = {},
                    returnToChannelId = 30,
                    takeFocus = true,
                )
            }
        }

        compose.onNodeWithText("Channel 30").assertIsDisplayed().assertIsFocused()
    }
}
