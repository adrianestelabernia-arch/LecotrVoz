package com.lectorvoz.app

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

fun fmtTime(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return when {
        h > 0 -> "$h h $m min"
        m > 0 -> "$m min"
        else -> "<1 min"
    }
}

// =============================================================== BIBLIOTECA
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: LibraryViewModel, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val docs by vm.docs.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val status by vm.importStatus.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var folder by rememberSaveable { mutableStateOf("") }
    var showNewFolder by remember { mutableStateOf(false) }
    var moveDoc by remember { mutableStateOf<DocItem?>(null) }
    var deleteDoc by remember { mutableStateOf<DocItem?>(null) }
    var deleteFolder by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.refresh() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.import(uri, folder)
    }

    BackHandler(folder.isNotEmpty()) { folder = "" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (folder.isEmpty()) "Mi biblioteca" else folder) },
                navigationIcon = {
                    if (folder.isNotEmpty()) IconButton(onClick = { folder = "" }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás")
                    }
                },
                actions = {
                    IconButton(onClick = { showNewFolder = true }) { Icon(Icons.Default.CreateNewFolder, "Nueva carpeta") }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(arrayOf("*/*")) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Añadir archivo") }
            )
        },
        bottomBar = { MiniPlayer(onOpen) }
    ) { pad ->
        val visible = docs.filter { it.folder == folder }
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
            if (folder.isEmpty()) {
                items(folders) { f ->
                    ListItem(
                        headlineContent = { Text(f) },
                        supportingContent = { Text("${docs.count { it.folder == f }} archivos") },
                        leadingContent = { Icon(Icons.Default.Folder, null) },
                        trailingContent = {
                            IconButton(onClick = { deleteFolder = f }) { Icon(Icons.Default.Delete, "Eliminar carpeta") }
                        },
                        modifier = Modifier.clickable { folder = f }
                    )
                }
            }
            if (visible.isEmpty() && (folder.isNotEmpty() || folders.isEmpty())) {
                item {
                    Text(
                        "Aún no hay archivos. Pulsa «Añadir archivo» (txt, pdf, epub, html, md). " +
                            "Los PDF escaneados se leen con OCR automáticamente.",
                        modifier = Modifier.padding(24.dp)
                    )
                }
            }
            items(visible, key = { it.id }) { d ->
                DocRow(d, onOpen = { onOpen(d.id) }, onMove = { moveDoc = d }, onDelete = { deleteDoc = d })
            }
        }
    }

    if (showNewFolder) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFolder = false },
            title = { Text("Nueva carpeta") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Nombre") }) },
            confirmButton = {
                TextButton(onClick = { if (name.isNotBlank()) vm.addFolder(name.trim()); showNewFolder = false }) { Text("Crear") }
            },
            dismissButton = { TextButton(onClick = { showNewFolder = false }) { Text("Cancelar") } }
        )
    }

    moveDoc?.let { d ->
        AlertDialog(
            onDismissRequest = { moveDoc = null },
            title = { Text("Mover a…") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TextButton(onClick = { vm.move(d.id, ""); moveDoc = null }) { Text("Sin carpeta (raíz)") }
                    folders.forEach { f -> TextButton(onClick = { vm.move(d.id, f); moveDoc = null }) { Text(f) } }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { moveDoc = null }) { Text("Cancelar") } }
        )
    }

    deleteDoc?.let { d ->
        AlertDialog(
            onDismissRequest = { deleteDoc = null },
            title = { Text("Eliminar archivo") },
            text = { Text("¿Eliminar «${d.title}» de la biblioteca?") },
            confirmButton = { TextButton(onClick = { vm.delete(d.id); deleteDoc = null }) { Text("Eliminar") } },
            dismissButton = { TextButton(onClick = { deleteDoc = null }) { Text("Cancelar") } }
        )
    }

    deleteFolder?.let { f ->
        AlertDialog(
            onDismissRequest = { deleteFolder = null },
            title = { Text("Eliminar carpeta") },
            text = { Text("Se eliminará la carpeta «$f». Sus archivos pasarán a la raíz.") },
            confirmButton = { TextButton(onClick = { vm.deleteFolder(f); deleteFolder = null }) { Text("Eliminar") } },
            dismissButton = { TextButton(onClick = { deleteFolder = null }) { Text("Cancelar") } }
        )
    }

    status?.let { s ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Importando") },
            text = {
                Column {
                    Text(s.message)
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { s.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { vm.cancelImport() }) { Text("Cancelar") } }
        )
    }

    error?.let { e ->
        AlertDialog(
            onDismissRequest = { vm.clearError() },
            title = { Text("No se pudo importar") },
            text = { Text(e) },
            confirmButton = { TextButton(onClick = { vm.clearError() }) { Text("Aceptar") } }
        )
    }
}

@Composable
private fun DocRow(d: DocItem, onOpen: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(d.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text("${d.format.uppercase()} · ${(d.percent * 100).toInt()} % leído")
                LinearProgressIndicator(
                    progress = { d.percent.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                )
            }
        },
        leadingContent = { Icon(Icons.Default.Description, null) },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Más") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Mover a carpeta") }, onClick = { menu = false; onMove() })
                    DropdownMenuItem(text = { Text("Eliminar") }, onClick = { menu = false; onDelete() })
                }
            }
        },
        modifier = Modifier.clickable { onOpen() }
    )
}

@Composable
private fun MiniPlayer(onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val rs by ReaderEngine.state.collectAsStateWithLifecycle()
    val id = rs.docId ?: return
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().clickable { onOpen(id) }) {
        Row(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(rs.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(
                    String.format(Locale.getDefault(), "%.1f %% · quedan %s", rs.percent * 100, fmtTime(rs.remainingSec)),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            IconButton(onClick = {
                if (rs.playing) ReaderEngine.pause() else ReaderService.send(ctx, ReaderService.ACTION_PLAY)
            }) { Icon(if (rs.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Reproducir/Pausa") }
        }
    }
}

// =============================================================== LECTOR
@Composable
fun ReaderScreen(docId: String, onBack: () -> Unit) {
    LaunchedEffect(docId) { ReaderEngine.open(docId) }
    val st by ReaderEngine.state.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    BackHandler { if (drawer.isOpen) scope.launch { drawer.close() } else onBack() }

    if (st.docId != docId) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        ReaderContent(st, drawer, onBack)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderContent(st: ReaderUiState, drawer: DrawerState, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var showSettings by remember { mutableStateOf(false) }
    var fontSize by rememberSaveable { mutableIntStateOf(19) }
    var lastSec by remember { mutableIntStateOf(-1) }
    val ctx = LocalContext.current

    LaunchedEffect(st.section, st.para) {
        if (st.paragraphs.isEmpty()) return@LaunchedEffect
        val idx = st.para.coerceIn(0, st.paragraphs.size - 1)
        if (lastSec != st.section) {
            listState.scrollToItem(idx)
            lastSec = st.section
        } else {
            listState.animateScrollToItem(idx)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = { ModalDrawerSheet { IndexPanel(st, drawer) } }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(st.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") } },
                    actions = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "Índice") }
                    }
                )
            },
            bottomBar = { PlayerBar(st) { showSettings = true } }
        ) { pad ->
            LazyColumn(
                state = listState,
                modifier = Modifier.padding(pad).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                itemsIndexed(st.paragraphs, key = { i, _ -> "${st.section}:$i" }) { i, text ->
                    val active = i == st.para
                    ParagraphItem(
                        text = text,
                        active = active,
                        ws = if (active) st.wordStart else 0,
                        we = if (active) st.wordEnd else 0,
                        fontSize = fontSize,
                        onTap = { off ->
                            val was = ReaderEngine.state.value.playing
                            ReaderEngine.seekTo(st.section, i, off, false)
                            if (!was) ReaderService.send(ctx, ReaderService.ACTION_PLAY)
                        }
                    )
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(st, fontSize, { fontSize = it }) { showSettings = false }
    }
}

@Composable
private fun IndexPanel(st: ReaderUiState, drawer: DrawerState) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    LaunchedEffect(drawer.currentValue) {
        if (drawer.currentValue == DrawerValue.Open) listState.scrollToItem((st.section - 2).coerceAtLeast(0))
    }
    Column(Modifier.fillMaxHeight()) {
        Text("Índice", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(20.dp))
        LazyColumn(state = listState) {
            itemsIndexed(st.sectionTitles) { i, t ->
                NavigationDrawerItem(
                    label = { Text("${i + 1}. $t", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    selected = i == st.section,
                    onClick = {
                        ReaderEngine.jumpToSection(i)
                        scope.launch { drawer.close() }
                    },
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun ParagraphItem(text: String, active: Boolean, ws: Int, we: Int, fontSize: Int, onTap: (Int) -> Unit) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val tap by rememberUpdatedState(onTap)
    val hl = MaterialTheme.colorScheme.primaryContainer
    val bg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    val annotated = remember(text, active, ws, we, hl) {
        buildAnnotatedString {
            append(text)
            if (active && we > ws && ws >= 0 && we <= text.length) {
                addStyle(SpanStyle(background = hl, fontWeight = FontWeight.Bold), ws, we)
            }
        }
    }
    Text(
        text = annotated,
        fontSize = fontSize.sp,
        lineHeight = (fontSize * 1.5f).sp,
        onTextLayout = { layout = it },
        modifier = Modifier
            .fillMaxWidth()
            .background(if (active) bg else Color.Transparent)
            .padding(vertical = 6.dp, horizontal = 4.dp)
            .pointerInput(text) {
                detectTapGestures { pos -> tap(layout?.getOffsetForPosition(pos) ?: 0) }
            }
    )
}

@Composable
private fun PlayerBar(st: ReaderUiState, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    var drag by remember { mutableStateOf<Float?>(null) }
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    String.format(Locale.getDefault(), "%.1f %% leído", (drag ?: st.percent) * 100),
                    style = MaterialTheme.typography.labelLarge
                )
                Text("Quedan ${fmtTime(st.remainingSec)}", style = MaterialTheme.typography.labelLarge)
            }
            Slider(
                value = drag ?: st.percent,
                onValueChange = { drag = it },
                onValueChangeFinished = {
                    drag?.let { ReaderEngine.seekToFraction(it) }
                    drag = null
                }
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { ReaderEngine.skip(-1) }) { Icon(Icons.Default.SkipPrevious, "Anterior") }
                FilledIconButton(
                    onClick = {
                        if (st.playing) ReaderEngine.pause() else ReaderService.send(ctx, ReaderService.ACTION_PLAY)
                    },
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        if (st.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (st.playing) "Pausa" else "Reproducir",
                        Modifier.size(36.dp)
                    )
                }
                IconButton(onClick = { ReaderEngine.skip(1) }) { Icon(Icons.Default.SkipNext, "Siguiente") }
                TextButton(onClick = onSettings) { Text(String.format(Locale.getDefault(), "%.1f×", st.rate)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsDialog(st: ReaderUiState, fontSize: Int, onFont: (Int) -> Unit, onDismiss: () -> Unit) {
    var rate by remember { mutableFloatStateOf(st.rate) }
    var menu by remember { mutableStateOf(false) }
    val locales = remember { ReaderEngine.availableLocales() }
    var tag by remember { mutableStateOf(ReaderEngine.localeTag()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Velocidad y lectura") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(String.format(Locale.getDefault(), "Velocidad: %.2fx", rate))
                Slider(
                    value = rate,
                    onValueChange = { rate = (it * 10).roundToInt() / 10f },
                    onValueChangeFinished = { ReaderEngine.setRate(rate) },
                    valueRange = 0.5f..3f,
                    steps = 24
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f).forEach { v ->
                        FilterChip(
                            selected = rate == v,
                            onClick = { rate = v; ReaderEngine.setRate(v) },
                            label = { Text("${v}x") }
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("Tamaño de letra: $fontSize")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { onFont((fontSize - 1).coerceAtLeast(12)) }) { Text("A−") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { onFont((fontSize + 1).coerceAtMost(34)) }) { Text("A+") }
                }
                Spacer(Modifier.height(16.dp))
                Text("Idioma de la voz")
                Box {
                    OutlinedButton(onClick = { menu = true }) {
                        Text(if (tag.isEmpty()) "Predeterminado del sistema" else Locale.forLanguageTag(tag).displayName)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Predeterminado del sistema") },
                            onClick = { menu = false; tag = ""; ReaderEngine.setLocale("") }
                        )
                        locales.forEach { l ->
                            DropdownMenuItem(
                                text = { Text(l.displayName) },
                                onClick = { menu = false; tag = l.toLanguageTag(); ReaderEngine.setLocale(tag) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
    )
}
