package com.xnotes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.xnotes.canvas.CanvasState
import com.xnotes.canvas.CanvasView
import com.xnotes.core.model.Document
import com.xnotes.platform.AndroidSurfaceFactory
import com.xnotes.ui.theme.Palette

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CanvasScreen()
        }
    }
}

@Composable
private fun CanvasScreen() {
    val state = remember {
        CanvasState(
            document = Document.blank(),
            surfaceFactory = AndroidSurfaceFactory(),
            palette = Palette.dark(),
        )
    }
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx -> CanvasView(ctx).apply { this.state = state } },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
