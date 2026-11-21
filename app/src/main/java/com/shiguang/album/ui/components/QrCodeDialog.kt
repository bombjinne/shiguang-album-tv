package com.shiguang.album.ui.components

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.shiguang.album.ui.theme.TvTheme
import android.graphics.Color as AndroidColor

/** 用 ZXing 生成二维码位图（纯本地，不依赖网络） */
fun generateQrBitmap(text: String, size: Int = 720): Bitmap? = runCatching {
    val hints = mapOf(
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1
    )
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        val offset = y * size
        for (x in 0 until size) {
            pixels[offset + x] = if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE
        }
    }
    Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, size, 0, 0, size, size)
    }
}.getOrNull()

/**
 * 扫码配置弹窗：显示局域网配置页地址 + 二维码。
 * 手机连同一个 Wi-Fi 扫码即可打开配置页，无需安装任何 App。
 */
@Composable
fun QrCodeDialog(
    url: String,
    hint: String = "手机与电视连同一个 Wi-Fi，扫码后在浏览器里配置",
    onDismiss: () -> Unit
) {
    val colors = TvTheme.colors
    val qr = remember(url) { generateQrBitmap(url) }
    val closeRequester = remember { FocusRequester() }

    BackHandler { onDismiss() }
    LaunchedEffect(Unit) { runCatching { closeRequester.requestFocus() } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.scrim),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(640.dp)
                .background(colors.surface, RoundedCornerShape(20.dp))
                .border(1.dp, colors.border, RoundedCornerShape(20.dp))
                .padding(horizontal = 40.dp, vertical = 32.dp)
        ) {
            Text(
                text = "手机扫码配置",
                color = colors.textPrimary,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = hint,
                color = colors.textSecondary,
                fontSize = 15.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))

            Box(
                modifier = Modifier
                    .size(320.dp)
                    .background(Color.White, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                if (qr != null) {
                    Image(
                        bitmap = qr.asImageBitmap(),
                        contentDescription = "配置二维码",
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text("二维码生成失败", color = Color.Black, fontSize = 14.sp)
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(
                text = url,
                color = colors.accent,
                fontSize = 17.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "也可以在手机浏览器手动输入上面的地址",
                color = colors.textSecondary,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(24.dp))
            TvButton(text = "关闭", focusRequester = closeRequester, onClick = onDismiss)
        }
    }
}
