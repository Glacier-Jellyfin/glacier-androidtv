package io.github.glacier_jellyfin.androidtv.diagnostics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.nayuki.qrcodegen.QrCode

/** [text] as a QR code: black on white with a quiet zone, as phone cameras expect. */
@Composable
fun QrImage(text: String, modifier: Modifier = Modifier) {
    val code = remember(text) { QrCode.encodeText(text, QrCode.Ecc.MEDIUM) }
    Canvas(modifier.background(Color.White).padding(18.dp)) {
        val cell = size.minDimension / code.size
        for (y in 0 until code.size) {
            for (x in 0 until code.size) {
                // A hair larger than the cell, so no seams show between neighbours.
                if (code.getModule(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}
