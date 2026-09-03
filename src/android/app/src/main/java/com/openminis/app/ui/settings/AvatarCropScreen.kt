package com.openminis.app.ui.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.exifinterface.media.ExifInterface
import com.openminis.app.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 5f
private const val MAX_DECODE_EDGE = 2048

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AvatarCropScreen(
    bitmap: Bitmap,
    onCancel: () -> Unit,
    onConfirm: (Bitmap) -> Unit,
) {
    BackHandler(onBack = onCancel)
    var viewportSize by remember(bitmap) { mutableStateOf(IntSize.Zero) }
    var zoom by remember(bitmap) { mutableStateOf(MIN_ZOOM) }
    var pan by remember(bitmap) { mutableStateOf(Offset.Zero) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }

    fun updateZoom(nextZoom: Float) {
        val bounded = nextZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val factor = bounded / zoom
        zoom = bounded
        pan = clampPan(
            bitmap = bitmap,
            viewport = viewportSize,
            zoom = bounded,
            requested = Offset(pan.x * factor, pan.y * factor),
        )
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Black,
                topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.soul_crop_title)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.soul_crop_cancel),
                        )
                    }
                },
                actions = {
                    IconButton(
                        enabled = viewportSize.width > 0 && viewportSize.height > 0,
                        onClick = {
                            createAvatarCrop(bitmap, viewportSize, zoom, pan)?.let(onConfirm)
                        },
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = stringResource(R.string.soul_crop_confirm),
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
                bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Filled.ZoomOut,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = zoom,
                        onValueChange = ::updateZoom,
                        valueRange = MIN_ZOOM..MAX_ZOOM,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                    Icon(
                        Icons.Filled.ZoomIn,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
                },
            ) { padding ->
                Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black)
                .onSizeChanged { next ->
                    viewportSize = next
                    pan = clampPan(bitmap, next, zoom, pan)
                }
                .pointerInput(bitmap, viewportSize) {
                    detectTransformGestures { _, gesturePan, gestureZoom, _ ->
                        val nextZoom = (zoom * gestureZoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                        val factor = nextZoom / zoom
                        val requested = Offset(
                            x = pan.x * factor + gesturePan.x,
                            y = pan.y * factor + gesturePan.y,
                        )
                        zoom = nextZoom
                        pan = clampPan(bitmap, viewportSize, nextZoom, requested)
                    }
                },
                ) {
            val viewport = IntSize(size.width.roundToInt(), size.height.roundToInt())
            val diameter = cropDiameter(viewport)
            val scale = coverScale(bitmap, diameter) * zoom
            val scaledWidth = bitmap.width * scale
            val scaledHeight = bitmap.height * scale
            val center = Offset(size.width / 2f, size.height / 2f)

            drawImage(
                image = image,
                dstOffset = IntOffset(
                    (center.x + pan.x - scaledWidth / 2f).roundToInt(),
                    (center.y + pan.y - scaledHeight / 2f).roundToInt(),
                ),
                dstSize = IntSize(
                    scaledWidth.roundToInt().coerceAtLeast(1),
                    scaledHeight.roundToInt().coerceAtLeast(1),
                ),
            )

            val radius = diameter / 2f
            val cropBounds = Rect(
                left = center.x - radius,
                top = center.y - radius,
                right = center.x + radius,
                bottom = center.y + radius,
            )
            val cropPath = Path().apply { addOval(cropBounds) }
            val shadePath = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size))
                addOval(cropBounds)
            }
            drawPath(shadePath, Color.Black.copy(alpha = 0.58f))
            clipPath(cropPath) {
                val gridColor = Color.White.copy(alpha = 0.45f)
                for (fraction in listOf(1f / 3f, 2f / 3f)) {
                    val x = cropBounds.left + diameter * fraction
                    val y = cropBounds.top + diameter * fraction
                    drawLine(gridColor, Offset(x, cropBounds.top), Offset(x, cropBounds.bottom), 1.dp.toPx())
                    drawLine(gridColor, Offset(cropBounds.left, y), Offset(cropBounds.right, y), 1.dp.toPx())
                }
            }
            drawCircle(
                color = Color.White.copy(alpha = 0.9f),
                radius = radius,
                center = center,
                style = Stroke(width = 2.dp.toPx()),
            )
                }
            }
        }
    }
}

private fun cropDiameter(viewport: IntSize): Float {
    if (viewport.width <= 0 || viewport.height <= 0) return 0f
    return min(viewport.width * 0.86f, viewport.height * 0.78f).coerceAtLeast(1f)
}

private fun coverScale(bitmap: Bitmap, diameter: Float): Float = max(
    diameter / bitmap.width.coerceAtLeast(1),
    diameter / bitmap.height.coerceAtLeast(1),
)

private fun clampPan(
    bitmap: Bitmap,
    viewport: IntSize,
    zoom: Float,
    requested: Offset,
): Offset {
    val diameter = cropDiameter(viewport)
    if (diameter <= 0f) return Offset.Zero
    val scale = coverScale(bitmap, diameter) * zoom
    val maxX = ((bitmap.width * scale - diameter) / 2f).coerceAtLeast(0f)
    val maxY = ((bitmap.height * scale - diameter) / 2f).coerceAtLeast(0f)
    return Offset(
        requested.x.coerceIn(-maxX, maxX),
        requested.y.coerceIn(-maxY, maxY),
    )
}

private fun createAvatarCrop(
    bitmap: Bitmap,
    viewport: IntSize,
    zoom: Float,
    pan: Offset,
): Bitmap? = runCatching {
    val diameter = cropDiameter(viewport)
    if (diameter <= 0f) return@runCatching null
    val scale = coverScale(bitmap, diameter) * zoom
    val sourceSide = (diameter / scale).roundToInt()
        .coerceIn(1, min(bitmap.width, bitmap.height))
    val scaledWidth = bitmap.width * scale
    val scaledHeight = bitmap.height * scale
    val imageLeft = viewport.width / 2f + pan.x - scaledWidth / 2f
    val imageTop = viewport.height / 2f + pan.y - scaledHeight / 2f
    val cropLeft = (viewport.width - diameter) / 2f
    val cropTop = (viewport.height - diameter) / 2f
    val sourceLeft = ((cropLeft - imageLeft) / scale).roundToInt()
        .coerceIn(0, bitmap.width - sourceSide)
    val sourceTop = ((cropTop - imageTop) / scale).roundToInt()
        .coerceIn(0, bitmap.height - sourceSide)
    Bitmap.createBitmap(bitmap, sourceLeft, sourceTop, sourceSide, sourceSide)
}.getOrNull()

/** Decode a picker URI efficiently and honor the camera's EXIF orientation. */
internal fun decodeAvatarBitmap(context: Context, uri: Uri): Bitmap? = runCatching {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

    var sampleSize = 1
    while (max(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_DECODE_EDGE) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val decoded = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    } ?: return@runCatching null

    val orientation = resolver.openInputStream(uri)?.use {
        runCatching {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    } ?: ExifInterface.ORIENTATION_NORMAL
    applyExifOrientation(decoded, orientation)
}.getOrNull()

private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    val transformed = when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> { matrix.setScale(-1f, 1f); true }
        ExifInterface.ORIENTATION_ROTATE_180 -> { matrix.setRotate(180f); true }
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> { matrix.setScale(1f, -1f); true }
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f); true }
        ExifInterface.ORIENTATION_ROTATE_90 -> { matrix.setRotate(90f); true }
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f); true }
        ExifInterface.ORIENTATION_ROTATE_270 -> { matrix.setRotate(-90f); true }
        else -> false
    }
    if (!transformed) return bitmap
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}
