package com.remoteclaude.server.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.awt.image.BufferedImage

@Composable
fun QrCodeImage(
    content: String,
    size: Int = 200,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(content, size) {
        generateQrBitmap(content, size)
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "QR Code: $content",
            modifier = modifier.size(size.dp),
        )
    }
}

private fun generateQrBitmap(content: String, size: Int): ImageBitmap? {
    return try {
        val writer = QRCodeWriter()
        val matrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until size) {
            for (y in 0 until size) {
                image.setRGB(x, y, if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        image.toComposeImageBitmap()
    } catch (e: Exception) {
        null
    }
}
