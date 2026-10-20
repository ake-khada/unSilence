package com.unsilence.app.ui.common

import androidx.compose.runtime.RememberObserver
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import org.junit.Assert.assertEquals
import org.junit.Test

class AvatarAnimationTest {
    @Test fun `late hidden result does not start until shown and resumed`() {
        val owner = AvatarAnimationOwner()
        val image = RecordingPainter()
        val painter = VisibilityAwareAvatarPainter(image, owner)
        owner.setResumed(true)
        painter.onRemembered()
        assertEquals(emptyList<String>(), image.calls)
        owner.setVisible(true)
        assertEquals(listOf("start"), image.calls)
        owner.setVisible(false)
        assertEquals(listOf("start", "stop"), image.calls)
    }

    @Test fun `visible image in started lifecycle does not start`() {
        val owner = AvatarAnimationOwner()
        val image = RecordingPainter()
        owner.setVisible(true)
        owner.attach(image)
        assertEquals(emptyList<String>(), image.calls)
        owner.setResumed(true)
        owner.setResumed(false)
        owner.setResumed(true)
        assertEquals(listOf("start", "stop", "start"), image.calls)
    }

    @Test fun `background then hidden resume cannot restart offscreen avatar`() {
        val owner = activeOwner()
        val image = RecordingPainter()
        owner.attach(image)
        owner.setResumed(false)
        owner.setVisible(false)
        owner.setResumed(true)
        assertEquals(listOf("start", "stop"), image.calls)
        owner.setVisible(true)
        assertEquals(listOf("start", "stop", "start"), image.calls)
    }

    @Test fun `unchanged eligibility and repeated remembers do no playback work`() {
        val owner = activeOwner()
        val image = RecordingPainter()
        owner.attach(image)
        repeat(10_000) {
            owner.setVisible(true)
            owner.setResumed(true)
            owner.attach(image)
        }
        assertEquals(listOf("start"), image.calls)
        owner.setVisible(false)
        repeat(10_000) {
            owner.setVisible(false)
            owner.setResumed(true)
        }
        assertEquals(listOf("start", "stop"), image.calls)
    }

    @Test fun `hide show retains the same delegate and does not detach the request`() {
        val owner = activeOwner()
        val image = RecordingPainter()
        val painter = VisibilityAwareAvatarPainter(image, owner)
        painter.onRemembered()
        repeat(3) {
            owner.setVisible(false)
            owner.setVisible(true)
        }
        assertEquals(listOf("start", "stop", "start", "stop", "start", "stop", "start"), image.calls)
        assertEquals(Size(24f, 24f), painter.intrinsicSize)
        painter.onForgotten()
        owner.setVisible(false)
        owner.setVisible(true)
        assertEquals(8, image.calls.size)
    }

    @Test fun `replacement stops previous before starting next and ignores stale forget`() {
        val owner = activeOwner()
        val calls = mutableListOf<String>()
        val old = RecordingPainter(calls, "old ")
        val next = RecordingPainter(calls, "new ")
        owner.attach(old)
        owner.attach(next)
        owner.detach(old)
        assertEquals(listOf("old start", "old stop", "new start"), calls)
        owner.setVisible(false)
        assertEquals(listOf("old start", "old stop", "new start", "new stop"), calls)
    }

    @Test fun `replacement while hidden never starts old or new result`() {
        val owner = AvatarAnimationOwner()
        val old = RecordingPainter()
        val next = RecordingPainter()
        owner.setResumed(true)
        owner.attach(old)
        owner.attach(next)
        owner.detach(old)
        assertEquals(emptyList<String>(), old.calls)
        assertEquals(emptyList<String>(), next.calls)
        owner.setVisible(true)
        assertEquals(emptyList<String>(), old.calls)
        assertEquals(listOf("start"), next.calls)
    }

    @Test fun `detach and abandonment stop exactly once and allow new attachment`() {
        val owner = activeOwner()
        val image = RecordingPainter()
        val painter = VisibilityAwareAvatarPainter(image, owner)
        painter.onRemembered()
        painter.onAbandoned()
        painter.onForgotten()
        assertEquals(listOf("start", "stop"), image.calls)
        painter.onRemembered()
        assertEquals(listOf("start", "stop", "start"), image.calls)
    }

    @Test fun `owner disposal stops even without Coil forget during crossfade`() {
        val owner = activeOwner()
        val image = RecordingPainter()
        owner.attach(image)
        owner.dispose()
        owner.dispose()
        owner.setVisible(false)
        owner.setResumed(false)
        owner.setVisible(true)
        owner.setResumed(true)
        owner.attach(image)
        val late = RecordingPainter()
        owner.attach(late)
        assertEquals(listOf("start", "stop"), image.calls)
        assertEquals(emptyList<String>(), late.calls)
    }

    @Test fun `never started result is not spuriously stopped on disposal`() {
        val owner = AvatarAnimationOwner()
        val image = RecordingPainter()
        owner.attach(image)
        owner.dispose()
        assertEquals(emptyList<String>(), image.calls)
    }

    @Test fun `two avatar owners have independent playback permission`() {
        val visible = activeOwner()
        val hidden = AvatarAnimationOwner()
        val visibleImage = RecordingPainter()
        val hiddenImage = RecordingPainter()
        visible.attach(visibleImage)
        hidden.attach(hiddenImage)
        hidden.dispose()
        assertEquals(listOf("start"), visibleImage.calls)
        assertEquals(emptyList<String>(), hiddenImage.calls)
        visible.setVisible(false)
        assertEquals(listOf("start", "stop"), visibleImage.calls)
    }

    private fun activeOwner() = AvatarAnimationOwner().apply {
        setVisible(true)
        setResumed(true)
    }

    private class RecordingPainter(
        val calls: MutableList<String> = mutableListOf(),
        private val prefix: String = "",
    ) : Painter(), RememberObserver {
        override val intrinsicSize = Size(24f, 24f)
        override fun DrawScope.onDraw() = Unit
        override fun onRemembered() { calls += "${prefix}start" }
        override fun onForgotten() { calls += "${prefix}stop" }
        override fun onAbandoned() { calls += "${prefix}abandon" }
    }
}
