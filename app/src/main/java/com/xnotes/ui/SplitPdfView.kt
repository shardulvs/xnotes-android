package com.xnotes.ui

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.platform.AndroidRasterSurface
import com.xnotes.platform.PdfSource
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SplitPdfView(editor: Editor) {
    val palette = LocalPalette.current
    val src = editor.splitPdfSource

    if (src == null) {
        // Placeholder / Empty state
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.bg.toComposeColor()),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(24.dp)
            ) {
                Icon(
                    imageVector = XnotesIcons.file,
                    contentDescription = "Document icon",
                    tint = palette.textDim.toComposeColor(),
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Split Screen PDF Viewer",
                    color = palette.text.toComposeColor(),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Load a reference PDF file to view alongside your note.",
                    color = palette.textDim.toComposeColor(),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = { editor.onRequestOpenSplitPdf?.invoke() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.accent.toComposeColor(),
                        contentColor = palette.bg.toComposeColor()
                    )
                ) {
                    Text("Choose PDF")
                }
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(
                    onClick = { editor.closeSplitScreen() }
                ) {
                    Text("Close Split Screen", color = palette.textDim.toComposeColor())
                }
            }
        }
    } else {
        // Active PDF Viewer state
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        var tocOpen by remember { mutableStateOf(false) }

        // Sync first visible item index to editor state
        LaunchedEffect(listState.firstVisibleItemIndex) {
            editor.splitPdfPageIndex = listState.firstVisibleItemIndex
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.bg.toComposeColor())
        ) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .background(palette.panel.toComposeColor())
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = editor.splitPdfTitle,
                    color = palette.text.toComposeColor(),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (editor.splitPdfToc.isNotEmpty()) {
                    Box {
                        IconButton(onClick = { tocOpen = true }) {
                            Icon(
                                imageVector = XnotesIcons.contents,
                                contentDescription = "Table of contents",
                                tint = palette.textDim.toComposeColor()
                            )
                        }
                        DropdownMenu(
                            expanded = tocOpen,
                            onDismissRequest = { tocOpen = false },
                            modifier = Modifier
                                .width(280.dp)
                                .background(palette.menuBg.toComposeColor())
                        ) {
                            editor.splitPdfToc.forEach { entry ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "  ".repeat(entry.level) + entry.title,
                                            color = palette.text.toComposeColor(),
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    onClick = {
                                        tocOpen = false
                                        if (entry.destPage >= 0) {
                                            scope.launch {
                                                listState.animateScrollToItem(entry.destPage)
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
                IconButton(onClick = { editor.closeSplitScreen() }) {
                    Icon(
                        imageVector = XnotesIcons.close,
                        contentDescription = "Close Split Screen",
                        tint = palette.textDim.toComposeColor()
                    )
                }
            }

            // Navigation Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(palette.panel.toComposeColor().copy(alpha = 0.8f))
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = {
                        val current = listState.firstVisibleItemIndex
                        if (current > 0) {
                            scope.launch { listState.animateScrollToItem(current - 1) }
                        }
                    },
                    enabled = listState.firstVisibleItemIndex > 0
                ) {
                    Icon(
                        imageVector = XnotesIcons.prev,
                        contentDescription = "Previous Page",
                        tint = if (listState.firstVisibleItemIndex > 0) palette.text.toComposeColor() else palette.textDim.toComposeColor().copy(alpha = 0.5f)
                    )
                }

                Text(
                    text = "Page ${editor.splitPdfPageIndex + 1} of ${editor.splitPdfPageCount}",
                    color = palette.text.toComposeColor(),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.SansSerif
                )

                IconButton(
                    onClick = {
                        val current = listState.firstVisibleItemIndex
                        if (current < editor.splitPdfPageCount - 1) {
                            scope.launch { listState.animateScrollToItem(current + 1) }
                        }
                    },
                    enabled = listState.firstVisibleItemIndex < editor.splitPdfPageCount - 1
                ) {
                    Icon(
                        imageVector = XnotesIcons.next,
                        contentDescription = "Next Page",
                        tint = if (listState.firstVisibleItemIndex < editor.splitPdfPageCount - 1) palette.text.toComposeColor() else palette.textDim.toComposeColor().copy(alpha = 0.5f)
                    )
                }
            }

            // PDF Scrollable Canvas View with Zoom + Pan gestures
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            if (scale > 1f) {
                                offset = offset + pan
                            } else {
                                offset = Offset.Zero
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                scale = 1f
                                offset = Offset.Zero
                            }
                        )
                    }
            ) {
                val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        )
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        items(editor.splitPdfPageCount) { index ->
                            SplitPdfPageItem(
                                source = src,
                                pageIndex = index,
                                containerWidthPx = widthPx
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SplitPdfPageItem(
    source: PdfSource,
    pageIndex: Int,
    containerWidthPx: Int
) {
    val (wPts, hPts) = remember(source, pageIndex) { source.pageSizePoints(pageIndex) }
    val aspect = wPts.toFloat() / hPts.toFloat()
    
    // Scale slightly down from container width to leave margins
    val paddingPx = 48
    val targetWidth = (containerWidthPx - paddingPx).coerceAtLeast(1)
    val targetHeight = (targetWidth / aspect).toInt().coerceAtLeast(1)

    val density = LocalDensity.current
    val targetWidthDp = remember(targetWidth) { with(density) { targetWidth.toDp() } }
    val targetHeightDp = remember(targetHeight) { with(density) { targetHeight.toDp() } }

    val surfaceState = produceState<AndroidRasterSurface?>(null, source, pageIndex, targetWidth, targetHeight) {
        val surface = withContext(Dispatchers.IO) {
            source.renderPage(pageIndex, targetWidth, targetHeight)
        }
        value = surface
        awaitDispose {
            surface?.recycle()
        }
    }

    val surface = surfaceState.value
    Box(
        modifier = Modifier
            .size(targetWidthDp, targetHeightDp)
            .border(1.dp, LocalPalette.current.border.toComposeColor(), RoundedCornerShape(4.dp))
            .background(LocalPalette.current.bg.toComposeColor())
    ) {
        if (surface != null && !surface.bitmap.isRecycled) {
            val imageBitmap = remember(surface) { surface.bitmap.asImageBitmap() }
            Image(
                bitmap = imageBitmap,
                contentDescription = "PDF Page ${pageIndex + 1}",
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = LocalPalette.current.accent.toComposeColor(),
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
