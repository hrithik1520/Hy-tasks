package com.hy.assistant.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hy.assistant.MainViewModel
import com.hy.assistant.tools.Terminal
import com.hy.assistant.tools.TerminalBackend

private val TermBg = Color(0xFF0B0D0F)
private val TermFg = Color(0xFFD7DADF)
private val TermDim = Color(0xFF7D838C)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(vm: MainViewModel, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries by Terminal.entries.collectAsState()
    val proposed by Terminal.proposed.collectAsState()
    val settings by vm.settings.data.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    var permTick by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val backend = settings.terminalBackend

    val termuxInstalled = remember(permTick) { Terminal.isTermuxInstalled(context) }
    val termuxAllowed = remember(permTick) { Terminal.hasTermuxPermission(context) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permTick++ }

    fun run(cmd: String) {
        Terminal.run(context.applicationContext, scope, cmd, backend)
    }

    LaunchedEffect(entries.size, entries.lastOrNull()?.output?.length) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Terminal") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { TextButton(onClick = Terminal::clear) { Text("Clear") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Run in:", style = MaterialTheme.typography.labelMedium)
                TerminalBackend.entries.forEach { b ->
                    FilterChip(
                        selected = backend == b,
                        onClick = { vm.settings.update { it.copy(terminalBackend = b) } },
                        label = { Text(b.label) },
                    )
                }
            }

            if (backend == TerminalBackend.TERMUX && (!termuxInstalled || !termuxAllowed)) {
                SectionCard(Modifier.padding(12.dp)) {
                    Text(if (!termuxInstalled) "Termux not found" else "Connect Termux", style = MaterialTheme.typography.titleSmall)
                    Text(Terminal.TERMUX_SETUP, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.padding(4.dp))
                    Row {
                        if (termuxInstalled) {
                            Button(onClick = { permLauncher.launch(Terminal.TERMUX_PERMISSION) }) { Text("Allow Termux access") }
                            Spacer(Modifier.width(8.dp))
                        }
                        OutlinedButton(onClick = { permTick++ }) { Text("Check again") }
                    }
                }
            } else if (backend == TerminalBackend.LOCAL && entries.isEmpty() && proposed == null) {
                Text(
                    "Runs in Alfrid's own sandbox with Android's built-in tools (ls, cat, df, ps, ping, getprop…). " +
                        "For a full Linux (pkg, python, git, /sdcard) switch to Termux.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .background(TermBg, RoundedCornerShape(10.dp)),
                contentPadding = PaddingValues(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(entries, key = { it.id }) { e ->
                    SelectionContainer {
                        Column {
                            Text("${if (e.backend == TerminalBackend.TERMUX) "termux" else "sh"} $ ${e.command}", color = Color(0xFF7FD48A), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                            if (e.output.isNotEmpty()) Text(e.output.trimEnd(), color = TermFg, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            Text(
                                when {
                                    e.running -> "running…"
                                    e.exitCode == 0 -> "exit 0"
                                    else -> "exit ${e.exitCode}"
                                },
                                color = if (!e.running && e.exitCode != 0) Color(0xFFFF6B6B) else TermDim,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }

            // AI-proposed command: shown in full, runs only on tap.
            proposed?.let { p ->
                SectionCard(Modifier.padding(12.dp)) {
                    Text("Alfrid suggests running:", style = MaterialTheme.typography.titleSmall)
                    Text(p.command, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 6.dp))
                    if (p.warnings.isNotEmpty()) {
                        Text("⚠ This command ${p.warnings.joinToString(", ")}.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = Terminal::dismissProposal) { Text("Cancel") }
                        TextButton(onClick = { input = p.command; Terminal.dismissProposal() }) { Text("Edit") }
                        Button(
                            onClick = { run(p.command) },
                            colors = if (p.warnings.isNotEmpty()) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
                        ) { Text("Run") }
                    }
                }
            }

            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    placeholder = { Text("$ command") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onGo = { run(input); input = "" }),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { run(input); input = "" }, enabled = input.isNotBlank()) { Text("Run") }
            }
            Text(
                "Tip: ask Alfrid on the home screen, e.g. \"check storage in termux\" — it proposes the command here.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
            )
        }
    }
}
