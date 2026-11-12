/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.design

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode

/**
 * Settee's theme. Always dark: there's no light scheme to choose.
 *
 * Colours, type and shapes are plain tokens ([SetteeColors], [SetteeType], [SetteeShapes]) that
 * screens read directly, and Settee's [Text] picks the face its locale needs by itself. What the
 * theme does provide is [NoIndication] for anything clickable: every Settee component draws its
 * own Highlight, and without Material, Compose's fallback would lay a grey shade over it.
 */
@Composable
fun SetteeTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalIndication provides NoIndication, content = content)
}

/** Draws nothing for focus or presses: the component's own Highlight shows them (docs/LOOK.md). */
private object NoIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = NoIndicationNode()

    override fun equals(other: Any?) = other === this

    override fun hashCode() = System.identityHashCode(this)
}

private class NoIndicationNode :
    Modifier.Node(),
    DrawModifierNode {
    override fun ContentDrawScope.draw() = drawContent()
}
