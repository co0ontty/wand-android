package com.wand.app.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wand.app.ui.theme.WandColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Image selection belongs to this editor instance; closing it cancels a late decode. */
@Composable
fun EmployeeAvatarPicker(avatar: String, name: String, enabled: Boolean, onChange: (String) -> Unit, onBusyChange: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentChange by rememberUpdatedState(onChange)
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(busy) { onBusyChange(busy) }
    DisposableEffect(Unit) { onDispose { onBusyChange(false) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            busy = true; error = null
            try {
                val data = withContext(Dispatchers.IO) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取这张图片" }
                    val options = BitmapFactory.Options().apply {
                        var sample = 1
                        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
                        inSampleSize = sample
                    }
                    val source = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                        ?: error("无法读取这张图片")
                    val edge = minOf(source.width, source.height)
                    val square = Bitmap.createBitmap(source, (source.width - edge) / 2, (source.height - edge) / 2, edge, edge)
                    val small = Bitmap.createScaledBitmap(square, 128, 128, true)
                    try {
                        val bytes = ByteArrayOutputStream().use { output -> small.compress(Bitmap.CompressFormat.JPEG, 88, output); output.toByteArray() }
                        "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                    } finally {
                        if (small !== square) small.recycle()
                        if (square !== source) square.recycle()
                        source.recycle()
                    }
                }
                require(data.length <= 60_000) { "图片过大，请换一张图片" }
                currentChange(data)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "图片处理失败，原头像已保留" }
            finally { busy = false }
        }
    }
    Column(Modifier.padding(top = 12.dp)) {
        Text("头像", style = MaterialTheme.typography.labelMedium, color = WandColors.textSecondary)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(8) { index ->
                IconButton(onClick = { error = null; currentChange("cat:$index") }, enabled = enabled && !busy,
                    modifier = Modifier.semantics { contentDescription = "头像 ${index + 1}"; selected = avatar == "cat:$index" }) {
                    EmployeeAvatar("preview", name, "cat:$index", size = 32.dp)
                }
            }
        }
        Row {
            WandButton(if (busy) "正在处理图片…" else "上传头像", { picker.launch("image/*") }, enabled = enabled && !busy, variant = WandButtonVariant.Text)
            WandButton("重置", { currentChange("") }, enabled = enabled && !busy, variant = WandButtonVariant.Text)
        }
        error?.let { Text(it, color = WandColors.danger, style = MaterialTheme.typography.bodySmall) }
    }
}
