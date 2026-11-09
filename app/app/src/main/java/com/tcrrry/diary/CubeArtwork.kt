package com.tcrrry.diary

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import kotlin.math.*

private class CubeScratch {
    val unitSize = 320f
    val corner = unitSize * 0.18f
    val srcCorners = floatArrayOf(
        0f, 0f,
        unitSize, 0f,
        unitSize, unitSize,
        0f, unitSize
    )
    val dstCorners = FloatArray(8)
    val imgSrcCorners = FloatArray(8)
    val baseRect = android.graphics.RectF(0f, 0f, unitSize, unitSize)
    val matrix = android.graphics.Matrix()
    val imgMatrix = android.graphics.Matrix()
    val androidPath = android.graphics.Path()
    val composePath = androidPath.asComposePath()
    val bitmapPaint = android.graphics.Paint(
        android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG
    )
}

/**
 * Renders a sleek, thin 3D rounded album card with perspective foreshortening,
 * crisp rounded side/bottom thickness, bevel highlight, and soft drop shadow.
 * Uses pre-allocated scratch buffers so 120Hz trajectory animation performs zero per-frame allocations.
 */
@Composable internal fun CubeArtwork(
    c: DiaryController,
    id: String,
    yaw: Float,
    pitch: Float,
    modifier: Modifier = Modifier
) {
    var loaded by remember(id) { mutableStateOf(c.images.peek(id, 320)) }
    val bitmap = loaded ?: c.images.peek(id, 320)
    DisposableEffect(id, c.imageNetwork, c.imageEpoch, c.report.lastSync) {
        val sub = c.images.load(id, 320) { img -> if (img != null) loaded = img }
        onDispose { sub.close() }
    }
    val scratch = remember { CubeScratch() }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val accent = Accent
    Canvas(modifier) {
        val minDim = size.minDimension
        if (minDim <= 1f) return@Canvas
        val scale = minDim * 0.43f
        val cx = size.width * 0.51f
        val cy = size.height * 0.48f
        val ry = yaw * PI.toFloat() / 180f
        val rx = pitch * PI.toFloat() / 180f
        val cosRy = cos(ry)
        val sinRy = sin(ry)
        val cosRx = cos(rx)
        val sinRx = sin(rx)
        val halfDepth = 0.13f

        fun project(x: Float, y: Float, z: Float, yShift: Float = 0f, xShift: Float = 0f): Offset {
            val fx = x * cosRy
            val fy = y * cosRx
            val fz = -x * sinRy * cosRx - y * sinRx
            val p = 5.2f / (5.2f - fz).coerceAtLeast(1.5f)
            val depthDelta = z - halfDepth
            val extrudeX = depthDelta * sinRy * 0.48f + xShift
            val extrudeY = -depthDelta * sinRx * 0.42f + yShift
            return Offset(
                cx + (fx * p + extrudeX) * scale,
                cy + (fy * p + extrudeY) * scale
            )
        }

        val dst = scratch.dstCorners
        val matrix = scratch.matrix
        val androidPath = scratch.androidPath
        val composePath = scratch.composePath

        fun buildSlicePath(z: Float, yShift: Float = 0f, xShift: Float = 0f, expand: Float = 1f): Boolean {
            val a = project(-expand, -expand, z, yShift, xShift)
            val b = project(expand, -expand, z, yShift, xShift)
            val e = project(expand, expand, z, yShift, xShift)
            val d = project(-expand, expand, z, yShift, xShift)
            dst[0] = a.x; dst[1] = a.y
            dst[2] = b.x; dst[3] = b.y
            dst[4] = e.x; dst[5] = e.y
            dst[6] = d.x; dst[7] = d.y
            if (!matrix.setPolyToPoly(scratch.srcCorners, 0, dst, 0, 4)) return false
            androidPath.reset()
            androidPath.addRoundRect(scratch.baseRect, scratch.corner, scratch.corner, android.graphics.Path.Direction.CW)
            androidPath.transform(matrix)
            return true
        }

        // 1. Soft multi-layered 3D drop shadow beneath the slim card
        val shadowLayers = 4
        val shadowXBias = -sinRy * 0.04f
        for (s in shadowLayers downTo 1) {
            val fraction = s.toFloat() / shadowLayers
            val expand = 0.96f + fraction * 0.07f
            val yDrop = 0.07f + fraction * 0.09f
            if (buildSlicePath(-halfDepth - 0.02f * fraction, yShift = yDrop, xShift = shadowXBias * fraction, expand = expand)) {
                val shadowAlpha = (if (dark) 0.12f else 0.052f) * (1.15f - fraction * 0.65f)
                drawPath(
                    path = composePath,
                    color = if (dark) Color.Black.copy(alpha = shadowAlpha) else Color(0xff482d3b).copy(alpha = shadowAlpha)
                )
            }
        }

        // 2. Slim 3D rounded extrusion edge (back z = -halfDepth to front z = +halfDepth)
        val steps = 10
        val sideHighlight = if (dark) Color(0xff675b6e) else Color(0xffffffff)
        val sideUpper = if (dark) Color(0xff524858) else Color(0xfff9eff4)
        val sideLower = if (dark) Color(0xff38303d) else Color(0xffe2cdd9)
        val sideBottom = if (dark) Color(0xff241f29) else Color(0xffc9b0bf)

        val sideDir = if (yaw >= 0f) -1f else 1f
        val gradStart = Offset(cx + sideDir * scale * 1.05f, cy - scale * 0.95f)
        val gradEnd = Offset(cx - sideDir * scale * 0.80f, cy + scale * 1.10f)

        if (buildSlicePath(-halfDepth)) {
            drawPath(
                path = composePath,
                color = if (dark) Color.Black.copy(alpha = 0.38f) else Color(0xff9c7f90).copy(alpha = 0.26f),
                style = Stroke(width = 0.8.dp.toPx())
            )
        }

        for (i in 0 until steps) {
            val t = i.toFloat() / steps
            val z = -halfDepth + (2f * halfDepth) * t
            if (buildSlicePath(z)) {
                val ao = 0.85f + 0.15f * t
                val c1 = lerp(sideUpper, sideHighlight, ao)
                val c2 = lerp(sideLower, sideUpper, ao)
                val c3 = lerp(sideBottom, sideLower, ao)
                drawPath(
                    path = composePath,
                    brush = Brush.linearGradient(
                        0.0f to c1,
                        0.50f to c2,
                        0.80f to c3,
                        1.0f to lerp(sideBottom, sideLower, 0.4f),
                        start = gradStart,
                        end = gradEnd
                    )
                )
            }
        }

        // 3. Front perspective-mapped rounded album face (z = +halfDepth)
        val a = project(-1f, -1f, halfDepth)
        val b = project(1f, -1f, halfDepth)
        val e = project(1f, 1f, halfDepth)
        val d = project(-1f, 1f, halfDepth)
        dst[0] = a.x; dst[1] = a.y
        dst[2] = b.x; dst[3] = b.y
        dst[4] = e.x; dst[5] = e.y
        dst[6] = d.x; dst[7] = d.y
        if (matrix.setPolyToPoly(scratch.srcCorners, 0, dst, 0, 4)) {
            androidPath.reset()
            androidPath.addRoundRect(scratch.baseRect, scratch.corner, scratch.corner, android.graphics.Path.Direction.CW)
            androidPath.transform(matrix)

            drawPath(
                path = composePath,
                brush = Brush.linearGradient(
                    colors = if (dark) listOf(Color(0xff352d39), Color(0xff241f28))
                    else listOf(Color(0xfffdf5f8), Color(0xffecd9e3)),
                    start = a,
                    end = e
                )
            )

            if (bitmap != null && !bitmap.isRecycled) {
                val imgSrc = scratch.imgSrcCorners
                val bw = bitmap.width.toFloat()
                val bh = bitmap.height.toFloat()
                imgSrc[0] = 0f; imgSrc[1] = 0f
                imgSrc[2] = bw; imgSrc[3] = 0f
                imgSrc[4] = bw; imgSrc[5] = bh
                imgSrc[6] = 0f; imgSrc[7] = bh
                if (scratch.imgMatrix.setPolyToPoly(imgSrc, 0, dst, 0, 4)) {
                    drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        val saveCount = native.save()
                        native.clipPath(androidPath)
                        native.drawBitmap(bitmap, scratch.imgMatrix, scratch.bitmapPaint)
                        native.restoreToCount(saveCount)
                    }
                }
            } else {
                val noteCenter = Offset((a.x + b.x + d.x + e.x) * 0.25f, (a.y + b.y + d.y + e.y) * 0.25f)
                drawCircle(
                    color = accent.copy(alpha = 0.22f),
                    radius = scale * 0.22f,
                    center = noteCenter
                )
            }

            // Crisp front bevel highlight separating face from the slim extruded side
            drawPath(
                path = composePath,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = if (dark) 0.12f else 0.15f),
                        Color.Transparent,
                        Color.Black.copy(alpha = if (dark) 0.14f else 0.05f)
                    ),
                    start = a,
                    end = e
                )
            )
            drawPath(
                path = composePath,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = if (dark) 0.55f else 0.94f),
                        Color.White.copy(alpha = if (dark) 0.20f else 0.48f)
                    ),
                    start = gradStart,
                    end = gradEnd
                ),
                style = Stroke(width = 1.05.dp.toPx())
            )
        }
    }
}
