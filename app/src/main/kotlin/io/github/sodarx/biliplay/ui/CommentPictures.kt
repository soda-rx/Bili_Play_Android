@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sodarx.biliplay.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import io.github.sodarx.biliplay.core.CommentImages
import io.github.sodarx.biliplay.core.CommentImageLayout
import io.github.sodarx.biliplay.core.CommentPicture

@Composable
internal fun pictureRequest(source: String, size: Int): ImageRequest {
    val context = LocalContext.current
    return remember(context, source, size) {
        ImageRequest.Builder(context).data(source).size(size).apply {
            if (source.startsWith("content:")) {
                diskCachePolicy(CachePolicy.DISABLED)
                memoryCachePolicy(CachePolicy.DISABLED)
            }
        }.build()
    }
}

@Composable
internal fun CommentImagePicker(s: AppState, m: BiliPlayViewModel) {
    var owner by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(CommentImages.MaxCount)) {
        m.addCommentImages(it, owner)
    }
    IconButton(enabled = !s.posting && s.commentImages.size < CommentImages.MaxCount, onClick = {
        owner = m.commentImageOwner()
        runCatching { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            .onFailure { m.message("无法打开系统照片选择器") }
    }) { Icon(Icons.Default.AddPhotoAlternate, "添加评论图片") }
}

@Composable
internal fun CommentAttachments(s: AppState, m: BiliPlayViewModel) {
    if (s.commentImages.isEmpty()) return
    var preview by remember(s.commentImages) { mutableStateOf<Int?>(null) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(s.commentImages, key = { it }) { source ->
                Box(Modifier.size(76.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    AsyncImage(pictureRequest(source, 256), "待发送图片",
                        Modifier.fillMaxSize().clickable { preview = s.commentImages.indexOf(source) }, contentScale = ContentScale.Crop)
                    FilledIconButton(onClick = { m.removeCommentImage(source) }, enabled = !s.posting,
                        modifier = Modifier.align(Alignment.TopEnd).size(30.dp)) {
                        Icon(Icons.Default.Close, "移除待发送图片", Modifier.size(18.dp))
                    }
                }
            }
        }
        Text(if (s.posting) {
            when {
                s.commentUploadIndex == 0 -> "正在处理图片…"
                s.commentUploadIndex > s.commentImages.size -> "正在发送评论…"
                else -> "正在上传图片 ${s.commentUploadIndex}/${s.commentImages.size}"
            }
        } else "${s.commentImages.size}/${CommentImages.MaxCount} · 点击发送后上传；压缩为静态图片并移除位置等元数据",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    preview?.let { PictureViewer(s.commentImages, it, onClose = { preview = null }) }
}

@Composable
internal fun CommentPictureGrid(pictures: List<CommentPicture>) {
    if (pictures.isEmpty()) return
    var preview by remember(pictures) { mutableStateOf<Int?>(null) }
    BoxWithConstraints(Modifier.widthIn(max = 360.dp).fillMaxWidth()) {
        val shown = pictures.take(CommentImages.MaxCount)
        val columns = CommentImageLayout.columns(shown.size)
        val availableWidth = maxWidth.value
        val tile = ((maxWidth - 4.dp * (columns - 1)) / columns).coerceAtLeast(0.dp)
        val density = LocalDensity.current
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            shown.chunked(columns).forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEachIndexed { columnIndex, picture ->
                        val index = rowIndex * columns + columnIndex
                        var failed by remember(picture.url) { mutableStateOf(false) }
                        val size = CommentImageLayout.single(picture.width, picture.height, availableWidth)
                        val imageModifier = if (shown.size == 1) Modifier.size(size.width.dp, size.height.dp)
                            else Modifier.size(tile)
                        val decodeSize = with(density) {
                            (if (shown.size == 1) maxOf(size.width, size.height).dp else tile).roundToPx()
                        }.coerceIn(64, 1024)
                        Box(imageModifier.clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable(onClickLabel = "查看第 ${index + 1} 张图片") { preview = index },
                            contentAlignment = Alignment.Center) {
                            AsyncImage(pictureRequest(picture.url, decodeSize), "评论图片 ${index + 1}", Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop, alignment = Alignment.TopCenter,
                                onError = { failed = true }, onSuccess = { failed = false })
                            if (failed) Icon(Icons.Default.BrokenImage, "图片加载失败，点击查看")
                            if (shown.size == 1 && picture.height.toLong() > picture.width.toLong() * 2) {
                                Surface(Modifier.align(Alignment.BottomEnd).padding(6.dp),
                                    shape = RoundedCornerShape(4.dp), color = Color.Black.copy(alpha = .6f)) {
                                    Text("长图", Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        color = Color.White, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    preview?.let { PictureViewer(pictures.map { it.url }, it, onClose = { preview = null }) }
}

@Composable
internal fun PictureViewer(sources: List<String>, initial: Int, onClose: () -> Unit) {
    if (sources.isEmpty()) return
    var index by remember(sources) { mutableIntStateOf(initial.coerceIn(sources.indices)) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "关闭图片", tint = Color.White) }
                Text("${index + 1} / ${sources.size}", Modifier.weight(1f), color = Color.White)
            }
            key(sources[index]) {
                var scale by remember { mutableFloatStateOf(1f) }
                var offset by remember { mutableStateOf(Offset.Zero) }
                var size by remember { mutableStateOf(IntSize.Zero) }
                var failed by remember { mutableStateOf(false) }
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()
                    .onSizeChanged { size = it }
                    .pointerInput(Unit) { detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        val limitX = size.width * (scale - 1) / 2
                        val limitY = size.height * (scale - 1) / 2
                        val next = offset + pan
                        offset = Offset(next.x.coerceIn(-limitX, limitX), next.y.coerceIn(-limitY, limitY))
                    } }
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = {
                        scale = if (scale > 1f) 1f else 2.5f
                        offset = Offset.Zero
                    }) }, contentAlignment = Alignment.Center) {
                    AsyncImage(pictureRequest(sources[index], 2048), "图片 ${index + 1}，可双击或双指缩放",
                        Modifier.fillMaxSize().graphicsLayer {
                            scaleX = scale; scaleY = scale
                            translationX = offset.x; translationY = offset.y
                        }, contentScale = ContentScale.Fit, onError = { failed = true })
                    if (failed) Text("图片无法加载，请检查网络或重新选择图片", color = Color.White)
                }
            }
            if (sources.size > 1) Row(Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { index-- }, enabled = index > 0) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null); Text("上一张")
                }
                TextButton(onClick = { index++ }, enabled = index < sources.lastIndex) {
                    Text("下一张"); Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
                }
            }
        }
    }
}
