package com.meni.whitelistfilter.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/*
 * סט אייקונים אחיד: קו בעובי אחיד, פינות עגולות, על רשת של 24 יחידות (כמו SF Symbols ברמת משקל "רגיל").
 * האייקונים צבועים בצבע הטקסט הנוכחי (Icon.tint), כך שהם מתאימים לכל מצב ולמצב כהה.
 * אייקונים שלא קיימים בסט הבסיסי של Material נבנים כאן. שאר האייקונים הם של Material Icons Core.
 */
object AppIcons {
    /** הגנה / סטטוס */
    val Shield: ImageVector by lazy {
        outline("Shield") {
            moveTo(12f, 2.5f)
            lineTo(4f, 5.5f)
            verticalLineTo(11.5f)
            curveTo(4f, 16.8f, 7.5f, 20.6f, 12f, 21.8f)
            curveTo(16.5f, 20.6f, 20f, 16.8f, 20f, 11.5f)
            verticalLineTo(5.5f)
            close()
        }
    }

    /** אפליקציות */
    val Apps: ImageVector by lazy {
        outline("Apps") {
            rect(4f, 4f, 7f, 7f)
            rect(13f, 4f, 7f, 7f)
            rect(4f, 13f, 7f, 7f)
            rect(13f, 13f, 7f, 7f)
        }
    }

    /** דומיינים / רשת */
    val Globe: ImageVector by lazy {
        outline("Globe") {
            ellipse(12f, 12f, 9f, 9f)
            ellipse(12f, 12f, 4f, 9f)
            moveTo(3f, 12f)
            lineTo(21f, 12f)
        }
    }

    /** יומן */
    val Journal: ImageVector by lazy {
        outline("Journal") {
            rect(4f, 3f, 16f, 18f)
            moveTo(8f, 8f)
            lineTo(16f, 8f)
            moveTo(8f, 12f)
            lineTo(16f, 12f)
            moveTo(8f, 16f)
            lineTo(13f, 16f)
        }
    }

    /** סינון / כוונון */
    val Tune: ImageVector by lazy {
        outline("Tune") {
            moveTo(4f, 6f)
            lineTo(20f, 6f)
            moveTo(4f, 12f)
            lineTo(20f, 12f)
            moveTo(4f, 18f)
            lineTo(20f, 18f)
            ellipse(15f, 6f, 2.5f, 2.5f)
            ellipse(9f, 12f, 2.5f, 2.5f)
            ellipse(16f, 18f, 2.5f, 2.5f)
        }
    }
}

private fun outline(name: String, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round
    ) {
        block()
    }.build()

private fun PathBuilder.rect(x: Float, y: Float, w: Float, h: Float) {
    moveTo(x, y)
    lineTo(x + w, y)
    lineTo(x + w, y + h)
    lineTo(x, y + h)
    close()
}

/** עיגול/אליפסה מקורבים בארבע עקומות ביזייה */
private fun PathBuilder.ellipse(cx: Float, cy: Float, rx: Float, ry: Float) {
    val k = 0.5523f
    moveTo(cx, cy - ry)
    curveTo(cx + rx * k, cy - ry, cx + rx, cy - ry * k, cx + rx, cy)
    curveTo(cx + rx, cy + ry * k, cx + rx * k, cy + ry, cx, cy + ry)
    curveTo(cx - rx * k, cy + ry, cx - rx, cy + ry * k, cx - rx, cy)
    curveTo(cx - rx, cy - ry * k, cx - rx * k, cy - ry, cx, cy - ry)
    close()
}
