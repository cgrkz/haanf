package app.haanf.ui.theme

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color

/** Outline icons drawn from the designs' own SVG paths, so no icon library is needed. */
object HaanfIcons {
    private fun icon(name: String, vararg paths: String, stroke: Float = 2f): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        paths.forEach { data ->
            builder.addPath(
                pathData = PathParser().parsePathString(data).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }

    val Program = icon(
        "program",
        "M5 5h14a2 2 0 0 1 2 2v12a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-12a2 2 0 0 1 2 -2z",
        "M3 10h18M8 3v4M16 3v4",
    )
    val Coach = icon("coach", "M4 5h16v11H9l-5 4z")
    val Progress = icon("progress", "M4 18l5-6 4 3 7-9")
    val Warning = icon("warning", "M12 3l9.5 17h-19z", "M12 10v4M12 17.5v.01")
    val Clock = icon("clock", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0", "M12 7v5l3 2")
    val Check = icon("check", "M4 12.5l5 5L20 6.5", stroke = 2.4f)
    val Settings = icon(
        "settings",
        "M4 7h10M18 7h2M4 17h2M10 17h10",
        "M14 7a2 2 0 1 0 4 0a2 2 0 1 0 -4 0",
        "M6 17a2 2 0 1 0 4 0a2 2 0 1 0 -4 0",
    )
    val Back = icon("back", "M15 5l-7 7 7 7")
    val Forward = icon("forward", "M9 5l7 7-7 7")
    val Mic = icon("mic", "M12 3a3 3 0 0 0 -3 3v6a3 3 0 0 0 6 0v-6a3 3 0 0 0 -3 -3z", "M5 11a7 7 0 0 0 14 0", "M12 18v3")
    val Send = icon("send", "M4 12h16", "M14 6l6 6-6 6")
    val Info = icon("info", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0", "M12 11v5M12 7.5v.01")
}
