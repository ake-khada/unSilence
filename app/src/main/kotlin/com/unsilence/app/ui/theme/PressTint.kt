package com.unsilence.app.ui.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import kotlinx.coroutines.launch

/** Shared bounded grey press tint; no expanding ripple or content scaling. */
internal object PressTint : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressTintNode(interactionSource)
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 0x554e53
}

private class PressTintNode(private val source: InteractionSource) : Modifier.Node(), DrawModifierNode {
    private var active by mutableStateOf(false)

    override fun onAttach() {
        coroutineScope.launch {
            val presses = mutableSetOf<PressInteraction.Press>()
            val focuses = mutableSetOf<FocusInteraction.Focus>()
            val hovers = mutableSetOf<HoverInteraction.Enter>()
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> presses += interaction
                    is PressInteraction.Release -> presses -= interaction.press
                    is PressInteraction.Cancel -> presses -= interaction.press
                    is FocusInteraction.Focus -> focuses += interaction
                    is FocusInteraction.Unfocus -> focuses -= interaction.focus
                    is HoverInteraction.Enter -> hovers += interaction
                    is HoverInteraction.Exit -> hovers -= interaction.enter
                }
                active = presses.isNotEmpty() || focuses.isNotEmpty() || hovers.isNotEmpty()
            }
        }
    }

    override fun onDetach() { active = false }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (active) drawRect(PressOverlay)
    }
}
