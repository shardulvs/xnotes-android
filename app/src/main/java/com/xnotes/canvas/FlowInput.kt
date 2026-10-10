package com.xnotes.canvas

import android.text.Editable
import android.text.Selection
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.util.Log
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.xnotes.core.text.FlowPos
import com.xnotes.core.text.FlowRange
import com.xnotes.core.text.TextFlow

/**
 * The flow's IME surface: a full plain-text mirror of the flow (paragraphs
 * joined by '\n') held in a [SpannableStringBuilder] that a [BaseInputConnection]
 * edits directly, so composing/autocorrect/swipe work against a real Editable.
 * A text watcher maps every mirror change back onto the model as a
 * [FlowTextController.applyReplace]; the model owns styles and paragraph
 * properties and never touches the mirror for them, so offsets stay stable.
 * Input restarts on exactly two things: a model-originated text change (undo,
 * paste, empty-line fill) detected by [reconcile]'s text comparison, and a caret
 * move out of a live composing run ([endComposing]). A key event's edit (a
 * hardware key, or a keyboard that sends its backspace as one) is patched into
 * the mirror the way a text field would take it, with no restart.
 *
 * The mirror must read exactly as the model does whenever the IME edits it,
 * since its offsets are mapped straight onto the model. The watcher checks that
 * before every edit and resyncs rather than land one where the text has moved.
 */
class FlowInput(
    private val view: CanvasView,
    private val session: FlowTextController,
    private val flow: () -> TextFlow,
) {
    val sessionActive: Boolean get() = session.active

    private val mirror = SpannableStringBuilder()
    private var suppressWatcher = false
    private var applyingFromMirror = false
    private var pendingStart = -1
    private var pendingBefore = 0
    private var pendingText = ""
    private var batchDepth = 0

    private var extractedToken = 0
    private var extractedMonitor = false
    private var cursorUpdatesMode = 0

    private var pendingOldText = ""
    private var pendingOldLength = 0

    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            if (suppressWatcher) return
            pendingOldText = s?.subSequence(start, start + count)?.toString().orEmpty()
            pendingOldLength = s?.length ?: 0
        }

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            if (suppressWatcher) return
            pendingStart = start
            pendingBefore = before
            pendingText = s.subSequence(start, start + count).toString()
        }

        override fun afterTextChanged(s: Editable) {
            if (suppressWatcher || pendingStart < 0) return
            val start = pendingStart
            val old = pendingOldText
            val new = pendingText
            pendingStart = -1
            if (!modelHolds(start, old, pendingOldLength)) {
                // Something edited the model behind the mirror's back: this edit's offsets
                // no longer name the text the keyboard meant, so drop it and resync.
                Log.w(TAG, "IME mirror drifted from the flow at $start, resyncing")
                view.post { resync() }
                return
            }
            // Composing IMEs resend the whole word-in-progress on every keystroke; trim
            // the untouched prefix/suffix so only the truly changed core reaches the
            // model, or the resent characters would re-insert under one uniform style.
            val (p, suf) = commonEnds(old, new)
            val insert = new.substring(p, new.length - suf)
            if (old.length - p - suf > 0 || insert.isNotEmpty()) {
                // Offsets refer to the pre-change text, which is exactly the model's state.
                val range = FlowRange(
                    flow().posAtGlobal(start + p),
                    flow().posAtGlobal(start + old.length - suf),
                )
                applyingFromMirror = true
                session.mirrorStale = false
                session.applyReplace(range, insert)
                applyingFromMirror = false
            }
            if (session.mirrorStale) {
                // A table boundary made the model diverge: keep its caret, rebuild the mirror
                // once the connection is done with this change.
                session.mirrorStale = false
                view.post { resync() }
                return
            }
            // The connection has already placed the mirror's selection; adopt it as truth.
            val selS = Selection.getSelectionStart(mirror).coerceAtLeast(0)
            val selE = Selection.getSelectionEnd(mirror).coerceAtLeast(0)
            session.setSelection(FlowRange(flow().posAtGlobal(selS), flow().posAtGlobal(selE)), syncIme = false)
            notifySelection()
        }
    }

    init {
        session.imeSync = { onCaretMovedExternally(finishComposing = true) }
        session.onEdited = { reconcile() }
        session.onTyped = { if (!applyingFromMirror) reconcile(restart = false) }
        session.requestIme = { showIme(restart = false) }
    }

    // --- session lifecycle (driven by the Editor's session callback) ---

    fun startSession() {
        rebuildMirror()
        showIme(restart = true)
    }

    /** While set, the soft keyboard stays down (a maths keyboard types instead); the session carries on. */
    var imeSuppressed = false
        set(on) {
            field = on
            if (on) imm()?.hideSoftInputFromWindow(view.windowToken, 0) else showIme(restart = true)
        }

    fun endSession() {
        imm()?.restartInput(view)
        imm()?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    /**
     * Show the soft keyboard, posted so focus lands before the request (calling both in
     * one breath races and the show is dropped). Also re-shows a keyboard the user
     * dismissed: every caret tap routes here.
     */
    private fun showIme(restart: Boolean) {
        if (imeSuppressed) return
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.post {
            if (!session.active) return@post
            view.requestFocus()
            if (restart) imm()?.restartInput(view)
            imm()?.showSoftInput(view, 0)
        }
    }

    /**
     * The model changed outside the mirror-driven path (undo, paste, fills, key events).
     * When the texts diverge, rebuild the mirror and restart input, or with [restart]
     * off patch just the difference in and report it as a caret move, which is all a
     * single key's edit needs. When they still match (a style-only or
     * mirror-originated change) just re-sync the selection cheaply.
     */
    fun reconcile(restart: Boolean = true) {
        if (!session.active) return
        if (session.mirrorStale && !applyingFromMirror) {
            session.mirrorStale = false
            resync()
            return
        }
        val text = if (applyingFromMirror) null else flow().plainText()
        if (text == null || text.contentEquals(mirror)) {
            onCaretMovedExternally(finishComposing = false)
            return
        }
        if (restart) {
            rebuildMirror()
            imm()?.restartInput(view)
            return
        }
        patchMirror(text)
        onCaretMovedExternally(finishComposing = true)
    }

    /** Rebuild the mirror from the model unconditionally and restart input. */
    private fun resync() {
        if (!session.active) return
        rebuildMirror()
        imm()?.restartInput(view)
    }

    /** Caret/selection moved without a text change (tap, arrows, drag selection). */
    private fun onCaretMovedExternally(finishComposing: Boolean) {
        val (s, e) = selectionGlobal()
        suppressWatcher = true
        Selection.setSelection(mirror, s.coerceIn(0, mirror.length), e.coerceIn(0, mirror.length))
        if (finishComposing) endComposing()
        suppressWatcher = false
        notifySelection()
    }

    /**
     * End the composing run the caret is walking away from. [BaseInputConnection]
     * replaces the composing span in preference to the selection, so a word left
     * composing at the old caret would swallow the next keystroke and drag the caret
     * back to it; the keyboard keeps a word buffer of its own that only a restart
     * clears. Costs a restart only on a caret move that lands mid-composition.
     */
    private fun endComposing() {
        if (BaseInputConnection.getComposingSpanStart(mirror) < 0 &&
            BaseInputConnection.getComposingSpanEnd(mirror) < 0
        ) {
            return
        }
        BaseInputConnection.removeComposingSpans(mirror)
        imm()?.restartInput(view)
    }

    private fun rebuildMirror() {
        suppressWatcher = true
        mirror.clearSpans()
        mirror.replace(0, mirror.length, flow().plainText())
        mirror.setSpan(watcher, 0, mirror.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        val (s, e) = selectionGlobal()
        Selection.setSelection(mirror, s.coerceIn(0, mirror.length), e.coerceIn(0, mirror.length))
        suppressWatcher = false
    }

    /** Rewrite only the stretch of the mirror that differs from [text], keeping its spans elsewhere. */
    private fun patchMirror(text: String) {
        val (p, suf) = commonEnds(mirror, text)
        suppressWatcher = true
        mirror.replace(p, mirror.length - suf, text, p, text.length - suf)
        suppressWatcher = false
    }

    /** Whether the model is [length] long and reads [old] at global [start], as the mirror did. */
    private fun modelHolds(start: Int, old: String, length: Int): Boolean {
        val f = flow()
        if (f.globalOffset(f.endPos()) != length) return false
        if (old.isEmpty()) return true
        val a = f.posAtGlobal(start)
        val b = f.posAtGlobal(start + old.length)
        if (a.para != b.para) return f.plainText().regionMatches(start, old, 0, old.length)
        return f.paragraphs[a.para].plainText().regionMatches(a.offset, old, 0, old.length)
    }

    /** Lengths of the common prefix of [a] and [b], and of the common suffix beyond it. */
    private fun commonEnds(a: CharSequence, b: CharSequence): Pair<Int, Int> {
        val max = minOf(a.length, b.length)
        var p = 0
        while (p < max && a[p] == b[p]) p++
        var s = 0
        while (s < max - p && a[a.length - 1 - s] == b[b.length - 1 - s]) s++
        return p to s
    }

    private fun selectionGlobal(): Pair<Int, Int> {
        val r = session.selection.normalized()
        return flow().globalOffset(clampPos(r.start)) to flow().globalOffset(clampPos(r.end))
    }

    private fun clampPos(pos: FlowPos): FlowPos {
        val f = flow()
        if (f.paragraphs.isEmpty()) return FlowPos.START
        val pi = pos.para.coerceIn(0, f.paragraphs.size - 1)
        return FlowPos(pi, pos.offset.coerceIn(0, f.paragraphs[pi].length))
    }

    private fun notifySelection() {
        if (batchDepth > 0) return // one report at endBatchEdit, not per step (Gboard swipe)
        val s = Selection.getSelectionStart(mirror).coerceAtLeast(0)
        val e = Selection.getSelectionEnd(mirror).coerceAtLeast(0)
        val cs = BaseInputConnection.getComposingSpanStart(mirror)
        val ce = BaseInputConnection.getComposingSpanEnd(mirror)
        imm()?.updateSelection(view, s, e, cs, ce)
        if (extractedMonitor) {
            imm()?.updateExtractedText(view, extractedToken, buildExtracted())
        }
        if (cursorUpdatesMode and InputConnection.CURSOR_UPDATE_MONITOR != 0) {
            reportCursorAnchor()
        }
    }

    /** Where the caret sits on screen, for handwriting IMEs (S Pen write-in-place). */
    private fun reportCursorAnchor() {
        val rect = session.caretViewportRect() ?: return
        val (s, e) = selectionGlobal()
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val matrix = android.graphics.Matrix().apply {
            postTranslate(loc[0].toFloat(), loc[1].toFloat())
        }
        val info = android.view.inputmethod.CursorAnchorInfo.Builder()
            .setSelectionRange(s, e)
            .setInsertionMarkerLocation(
                rect.left.toFloat(),
                rect.top.toFloat(),
                rect.bottom.toFloat(),
                rect.bottom.toFloat(),
                android.view.inputmethod.CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION,
            )
            .setMatrix(matrix)
            .build()
        imm()?.updateCursorAnchorInfo(view, info)
    }

    private fun buildExtracted(): ExtractedText = ExtractedText().apply {
        text = mirror.toString()
        startOffset = 0
        selectionStart = Selection.getSelectionStart(mirror).coerceAtLeast(0)
        selectionEnd = Selection.getSelectionEnd(mirror).coerceAtLeast(0)
        flags = 0
    }

    private fun imm(): InputMethodManager? =
        view.context.getSystemService(InputMethodManager::class.java)

    // --- the connection ---

    fun createInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT or
            EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE or
            EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES or
            EditorInfo.TYPE_TEXT_FLAG_AUTO_CORRECT
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION or EditorInfo.IME_FLAG_NO_FULLSCREEN
        val (s, e) = selectionGlobal()
        outAttrs.initialSelStart = s
        outAttrs.initialSelEnd = e
        batchDepth = 0
        return FlowInputConnection()
    }

    private inner class FlowInputConnection : BaseInputConnection(view, true) {
        override fun getEditable(): Editable = mirror

        override fun beginBatchEdit(): Boolean {
            batchDepth++
            return true
        }

        override fun endBatchEdit(): Boolean {
            if (batchDepth > 0) batchDepth--
            if (batchDepth == 0) notifySelection()
            return batchDepth > 0
        }

        override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean {
            cursorUpdatesMode = cursorUpdateMode
            if (cursorUpdateMode and InputConnection.CURSOR_UPDATE_IMMEDIATE != 0) {
                view.post { reportCursorAnchor() }
            }
            return true
        }

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText {
            // Samsung's keyboard needs a real extracted-text answer (and monitoring) or its
            // candidate strip goes blank; BaseInputConnection returns null by default.
            if (request != null && (flags and InputConnection.GET_EXTRACTED_TEXT_MONITOR) != 0) {
                extractedMonitor = true
                extractedToken = request.token
            }
            return buildExtracted()
        }

        override fun performContextMenuAction(id: Int): Boolean {
            if (id == android.R.id.paste) {
                onPaste()
                return true
            }
            return super.performContextMenuAction(id)
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            // Soft-keyboard backspace: paragraph-property rules (empty code line
            // turns plain) win over deleting the newline before it.
            if (beforeLength > 0 && afterLength == 0 && onBackspaceSpecial()) return true
            // Soft-keyboard forward delete: a block line keeps its properties
            // when the previous line merges into it.
            if (beforeLength == 0 && afterLength > 0 && onForwardDeleteSpecial()) return true
            return super.deleteSurroundingText(beforeLength, afterLength)
        }

        override fun closeConnection() {
            extractedMonitor = false
            super.closeConnection()
        }
    }

    /** IME-menu paste lands here; the Editor routes it to the shared text-paste path. */
    var onPaste: () -> Unit = {}

    /** Backspace hook: returns true when a paragraph-property rule consumed the key. */
    var onBackspaceSpecial: () -> Boolean = { false }

    /** Forward-delete hook: returns true when a paragraph-property rule consumed the key. */
    var onForwardDeleteSpecial: () -> Boolean = { false }

    private companion object {
        const val TAG = "xnotes.ime"
    }
}
