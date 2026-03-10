package com.unsilence.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

/**
 * Pubkey-derived identicon — Compose Canvas implementation.
 * - Hue:     first byte of pubkey hex → 0–360°
 * - Pattern: 5×5 symmetric grid (mirrored left↔right) derived from pubkey bytes
 *
 * Each pubkey produces a unique color AND unique pattern, making
 * users visually distinguishable even in dense notification stacks.
 */
@Composable
fun IdentIcon(
    pubkey: String,
    modifier: Modifier = Modifier,
) {
    val (color, grid) = remember(pubkey) { deriveIdenticon(pubkey) }

    Canvas(modifier = modifier.clip(CircleShape)) {
        // Background fill (AMOLED black)
        drawRect(Color(0xFF111111))

        val cellW = size.width  / GRID_SIZE
        val cellH = size.height / GRID_SIZE

        for (row in 0 until GRID_SIZE) {
            for (col in 0 until MIRROR_COLS) {
                if (!grid[row * MIRROR_COLS + col]) continue
                val mirrorCol = GRID_SIZE - 1 - col
                drawRect(color, Offset(col * cellW, row * cellH), Size(cellW, cellH))
                if (col != mirrorCol) {
                    drawRect(color, Offset(mirrorCol * cellW, row * cellH), Size(cellW, cellH))
                }
            }
        }
    }
}

// ── Internal ──────────────────────────────────────────────────────────────────

private const val GRID_SIZE   = 5      // 5×5 grid
private const val MIRROR_COLS = 3      // only 3 unique columns (cols 0-2 mirrored to 4-2)
private const val PATTERN_BITS = GRID_SIZE * MIRROR_COLS  // 15 bits

private data class Identicon(val color: Color, val grid: BooleanArray)

private fun deriveIdenticon(pubkey: String): Identicon {
    // Guard against short/invalid pubkeys
    val hex = pubkey.filter { it.isLetterOrDigit() }.take(64).padEnd(64, '0')

    // Color from first 2 hex chars (1 byte) → hue, fixed sat/lightness for vibrancy on dark bg
    val hue = (hex.substring(0, 2).toIntOrNull(16) ?: 0) * 360f / 255f
    val color = Color.hsl(hue, saturation = 0.72f, lightness = 0.58f)

    // Pattern from bytes starting at offset 2 (bytes 1–8)
    val grid = BooleanArray(PATTERN_BITS)
    for (i in 0 until PATTERN_BITS) {
        val byteStart = ((i / 8) + 1) * 2   // skip byte 0 (used for color)
        val bitIndex  = i % 8
        if (byteStart + 2 > hex.length) break
        val byte = hex.substring(byteStart, byteStart + 2).toIntOrNull(16) ?: 0
        grid[i] = (byte shr bitIndex) and 1 == 1
    }

    return Identicon(color, grid)
}
