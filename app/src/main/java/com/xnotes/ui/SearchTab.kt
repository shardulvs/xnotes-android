package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.search.SearchHit
import com.xnotes.core.search.SearchResults
import com.xnotes.core.search.SearchSnippet
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/**
 * The side panel's Search tab: the query and its toggles, where the current match stands with
 * prev/next, then every match grouped by page, each in a line of its context. The search session
 * lives exactly as long as the tab is on screen.
 */
@Composable
internal fun SearchTab(editor: Editor) {
    DisposableEffect(editor) {
        editor.openSearchSession()
        onDispose { editor.closeSearchSession() }
    }
    val focusManager = LocalFocusManager.current
    val results = editor.searchResults
    Column(Modifier.fillMaxSize()) {
        QueryField(editor)
        QueryBar(editor, results)
        Progress(results)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                editor.searchQuery.isBlank() || results == null -> Unit
                results.count == 0 -> if (results.done) EmptyHint(stringResource(R.string.no_matches))
                else -> MatchList(editor, results) { focusManager.clearFocus() }
            }
        }
    }
}

@Composable
private fun QueryField(editor: Editor) {
    val palette = LocalPalette.current
    val focus = remember { FocusRequester() }
    // The last query comes back selected, so typing replaces it.
    var field by remember { mutableStateOf(TextFieldValue(editor.searchQuery, TextRange(0, editor.searchQuery.length))) }
    LaunchedEffect(editor.searchFocusTick) {
        runCatching { focus.requestFocus() }
        field = field.copy(selection = TextRange(0, field.text.length))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 2.dp)
            .height(36.dp)
            .clip(CircleShape)
            .background(palette.surface.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), CircleShape)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(XnotesIcons.search, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (field.text.isEmpty()) {
                Text(stringResource(R.string.find_in_note), color = palette.textDim.toComposeColor(), fontSize = 13.sp, maxLines = 1)
            }
            BasicTextField(
                value = field,
                onValueChange = {
                    field = it
                    editor.searchFor(it.text)
                },
                singleLine = true,
                textStyle = TextStyle(color = palette.text.toComposeColor(), fontSize = 13.sp),
                cursorBrush = SolidColor(palette.accent.toComposeColor()),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { editor.searchStep(forward = true) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .onPreviewKeyEvent { e ->
                        // A keyboard's Enter steps on, Shift+Enter back.
                        if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) {
                            editor.searchStep(forward = !e.isShiftPressed)
                            true
                        } else {
                            false
                        }
                    },
            )
        }
        if (field.text.isNotEmpty()) {
            Icon(
                XnotesIcons.close, stringResource(R.string.clear_search),
                tint = palette.textDim.toComposeColor(),
                modifier = Modifier.size(16.dp).clip(CircleShape).clickable {
                    field = TextFieldValue("")
                    editor.searchFor("")
                },
            )
        }
    }
}

@Composable
private fun QueryBar(editor: Editor, results: SearchResults?) {
    val palette = LocalPalette.current
    val any = results != null && results.count > 0
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Toggle("Aa", stringResource(R.string.match_case), editor.searchMatchCase) { editor.toggleSearchMatchCase() }
        Toggle("W", stringResource(R.string.whole_words), editor.searchWholeWords) { editor.toggleSearchWholeWords() }
        Text(
            position(editor, results),
            color = palette.textDim.toComposeColor(),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        StepButton(XnotesIcons.chevronUp, stringResource(R.string.previous_match), any) { editor.searchStep(forward = false) }
        StepButton(XnotesIcons.chevronDown, stringResource(R.string.next_match), any) { editor.searchStep(forward = true) }
    }
}

/** "3 / 27" for the current match, "– / 27" before one is picked, "10000+" once a scan hit its cap. */
@Composable
private fun position(editor: Editor, results: SearchResults?): String {
    if (results == null || editor.searchQuery.isBlank()) return ""
    if (results.count == 0) return if (results.done) "" else stringResource(R.string.searching)
    val i = editor.searchCurrent?.let(results::indexOf)?.takeIf { it >= 0 }
    val total = if (results.capped) "${results.count}+" else "${results.count}"
    return stringResource(R.string.search_position, if (i == null) "–" else "${i + 1}", total)
}

@Composable
private fun Toggle(label: String, description: String, on: Boolean, onToggle: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .size(32.dp)
            .padding(3.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (on) palette.selectionBackground.toComposeColor() else Color.Transparent)
            .toggleable(value = on, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = (if (on) palette.selectionForeground else palette.textDim).toComposeColor(),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun StepButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(36.dp)) {
        Icon(icon, description, tint = (if (enabled) palette.text else palette.textDim).toComposeColor(), modifier = Modifier.size(18.dp))
    }
}

/** How far a scan has read the PDF; the room stays when there is nothing to show, so the list never jumps. */
@Composable
private fun Progress(results: SearchResults?) {
    val palette = LocalPalette.current
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(2.dp)) {
        if (results != null && !results.done && results.toRead > 0) {
            Box(Modifier.fillMaxSize().clip(CircleShape).background(palette.border.toComposeColor()))
            Box(
                Modifier
                    .fillMaxWidth(results.read.toFloat() / results.toRead)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(palette.accent.toComposeColor()),
            )
        }
    }
}

@Composable
private fun MatchList(editor: Editor, results: SearchResults, onPick: () -> Unit) {
    val palette = LocalPalette.current
    val current = editor.searchCurrent
    // Page numbers and matches in one list: an Int is a page's heading, a SearchHit one of its matches.
    val rows = remember(results) {
        ArrayList<Any>(results.count + results.pagesWithHits.size).apply {
            for (p in results.pagesWithHits) {
                add(p)
                addAll(results.hitsOn(p))
            }
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(current) {
        val i = rows.indexOfFirst { it === current }
        if (i >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == i }) listState.scrollToItem((i - 1).coerceAtLeast(0))
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        items(rows.size, key = { k -> rowKey(rows[k]) }) { k ->
            when (val row = rows[k]) {
                is SearchHit -> MatchRow(row, row === current) {
                    onPick()
                    editor.showSearchHit(row)
                }
                else -> Text(
                    stringResource(R.string.page_n, (row as Int) + 1),
                    color = palette.textDim.toComposeColor(),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 2.dp),
                )
            }
        }
    }
}

private fun rowKey(row: Any): Any = if (row is SearchHit) "h${row.page}:${row.target}:${row.start}" else "p$row"

@Composable
private fun MatchRow(hit: SearchHit, current: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val text = remember(hit) {
        val s = SearchSnippet.of(hit)
        buildAnnotatedString {
            append(s.text)
            addStyle(SpanStyle(fontWeight = FontWeight.Bold), s.matchStart, s.matchEnd)
        }
    }
    Text(
        text,
        color = (if (current) palette.selectionForeground else palette.text).toComposeColor(),
        fontSize = 12.sp,
        lineHeight = 16.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (current) palette.selectionBackground.toComposeColor() else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 5.dp),
    )
}
