package com.xnotes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.xnotes.canvas.CanvasState
import com.xnotes.canvas.CanvasView
import com.xnotes.canvas.InteractionController
import com.xnotes.core.history.History
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
    val context = LocalContext.current
    val view = remember { CanvasView(context) }
    remember {
        val state = CanvasState(Document.blank(), AndroidSurfaceFactory(), Palette.dark())
        view.state = state
        InteractionController(state, History(), requestRender = { view.requestRender() }).also { controller ->
            view.input = { event -> controller.onTouch(event) }
            view.drawOverlay = { renderer, _ -> controller.drawOverlay(renderer) }
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
    }
}
