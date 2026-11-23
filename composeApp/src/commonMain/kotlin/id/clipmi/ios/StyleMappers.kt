package id.clipmi.ios

import id.clipmi.android.data.CaptionStyleEntity
import id.clipmi.android.data.FrameStyleEntity
import id.clipmi.core.render.CaptionStyle
import id.clipmi.core.render.FrameStyle
import id.clipmi.core.render.TemplateArea
import kotlinx.serialization.json.Json

// Sama dengan toCore() di app/.../domain/Renderer.kt Android.

private val templateJson = Json { ignoreUnknownKeys = true }

fun CaptionStyleEntity.toCore() = CaptionStyle(
    name, fontFamily, fontSize, uppercase, primaryColor, outlineColor, highlightActiveWord, highlightStyle, highlightColor,
    upcomingColor, keywordHighlight, keywordColor, position, wordsPerLine, animation, effect, lineBox, lineBoxColor, lineBoxOpacity,
    highlightRadius = highlightRadius, highlightHeight = highlightHeight, highlightWidth = highlightWidth, highlightY = highlightY,
)

fun FrameStyleEntity.toCore() = FrameStyle(
    name = name, aspect = aspect, mode = mode, background = background, blurStrength = blurStrength,
    backgroundColor = backgroundColor, backgroundColor2 = backgroundColor2, backgroundPath = backgroundPath,
    templatePath = templatePath,
    templateArea = templateArea?.let { runCatching { templateJson.decodeFromString(TemplateArea.serializer(), it) }.getOrNull() },
    videoHeight = videoHeight, videoPosition = videoPosition, zoom = zoom, cornerRadius = cornerRadius, borderWidth = borderWidth,
    borderColor = borderColor, logoPath = logoPath, logoX = logoX, logoY = logoY, logoSize = logoSize, logoOpacity = logoOpacity,
    watermarkText = watermarkText, watermarkFont = watermarkFont, watermarkSize = watermarkSize, watermarkColor = watermarkColor,
    watermarkOpacity = watermarkOpacity, watermarkX = watermarkX, watermarkY = watermarkY, titleBar = titleBar,
    titleBarColor = titleBarColor, titleTextColor = titleTextColor, subtitleY = subtitleY,
    cropX = cropX, cropY = cropY, templateFill = templateFill,
)
