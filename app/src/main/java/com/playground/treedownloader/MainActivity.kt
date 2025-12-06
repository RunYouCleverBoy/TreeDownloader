package com.playground.treedownloader

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.playground.treedownloader.ui.theme.TreeDownloaderTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var preferencesManager: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferencesManager = PreferencesManager(this)
        enableEdgeToEdge()
        setContent {
            TreeDownloaderTheme {
                val progress = FileDownloader.downloadProgress.collectAsState()
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        Screen(innerPadding, preferencesManager, filenameFormatter("TreeDownload_")) { uri, folder, directoryType ->
                            downloadAllFilesTo(uri, folder, directoryType)
                        }
                        // Linear progress indicator at the bottom
                        if (progress.value > 0f && progress.value < 1f) {
                            LinearProgressIndicator(
                                progress = { progress.value },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.BottomCenter)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun filenameFormatter(@Suppress("SameParameterValue") prefix: String) =
        prefix + SimpleDateFormat("yyyyMMddhhmm", Locale.ROOT).format(Date(System.currentTimeMillis()))

    private fun downloadAllFilesTo(uri: Uri, folder: String, directoryType: FileDownloader.DirectoryType) {
        lifecycleScope.launch {
            FileDownloader.downloadAllFilesTo(this@MainActivity, uri, folder, directoryType)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("SameParameterValue")
@Composable
private fun Screen(
    innerPadding: PaddingValues,
    preferencesManager: PreferencesManager,
    defaultFilename: String,
    onDownload: (fromUri: Uri, toFolder: String, directoryType: FileDownloader.DirectoryType) -> Unit = { _, _, _ -> }
) {
    val savedIp = preferencesManager.ip.collectAsState(initial = "0.0.0.0")
    val savedPort = preferencesManager.port.collectAsState(initial = "8000")
    val savedFolder = preferencesManager.folder.collectAsState(initial = "")

    var ip by remember { mutableStateOf(savedIp.value) }
    var port by remember { mutableStateOf(savedPort.value) }
    var targetFolder by remember {
        val defaultFolder = defaultFilename
        mutableStateOf(savedFolder.value.ifEmpty { defaultFolder })
    }
    var directoryType by remember { mutableStateOf(FileDownloader.DirectoryType.DOWNLOADS) }
    var expanded by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    // Load saved values when they change from DataStore
    LaunchedEffect(savedIp.value) {
        ip = savedIp.value
    }
    LaunchedEffect(savedPort.value) {
        port = savedPort.value
    }
    LaunchedEffect(savedFolder.value) {
        if (savedFolder.value.isNotEmpty()) {
            targetFolder = savedFolder.value
        }
    }

    Column(modifier = Modifier.padding(paddingValues = innerPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLine(Modifier.fillMaxWidth(), stringResource(R.string.ip), KeyboardType.Decimal, ip) { newIp ->
            ip = newIp
            scope.launch {
                preferencesManager.saveIp(newIp)
            }
        }
        FieldLine(Modifier.fillMaxWidth(), stringResource(R.string.port), KeyboardType.Number, port) { newPort ->
            port = newPort
            scope.launch {
                preferencesManager.savePort(newPort)
            }
        }
        FieldLine(Modifier.fillMaxWidth(), stringResource(R.string.target_folder), KeyboardType.Text, targetFolder) { newFolder ->
            targetFolder = newFolder
            scope.launch {
                preferencesManager.saveFolder(newFolder)
            }
        }

        // Directory type dropdown
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(modifier = Modifier.width(100.dp), text = "Directory")
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                TextField(
                    readOnly = true,
                    value = directoryType.displayName,
                    onValueChange = { },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    FileDownloader.DirectoryType.entries.forEach { type ->
                        DropdownMenuItem(
                            text = { Text(type.displayName) },
                            onClick = {
                                directoryType = type
                                expanded = false
                            }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))
        Button(onClick = {
            onDownload(
                "http://$ip:$port".toUri(),
                targetFolder,
                directoryType
            )
        }, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.download))
        }
    }
}

@Composable
fun FieldLine(modifier: Modifier, caption: String, keyboardType: KeyboardType, initialValue: String, onValueChange: (String) -> Unit) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(modifier = Modifier.width(100.dp), text = caption)
        TextField(value = initialValue, keyboardOptions = KeyboardOptions(keyboardType = keyboardType), onValueChange = onValueChange)
    }
}
