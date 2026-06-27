package com.example.paperclipper

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.paperclipper.data.Clipping
import com.example.paperclipper.data.ClippingStatus
import com.example.paperclipper.data.CommentEntity
import com.example.paperclipper.data.TagEntity
import com.example.paperclipper.data.clippingsDir
import com.example.paperclipper.data.mimeTypeFor
import io.moyuru.cropify.Cropify
import io.moyuru.cropify.rememberCropifyState
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    // An image shared into the app from another app (ACTION_SEND). Read on launch and on each new
    // share intent; ClipperApp consumes it, imports it as a clipping, and clears it.
    private val sharedImageUri = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        sharedImageUri.value = extractSharedImage(intent)
        setContent {
            MaterialTheme {
                ClipperApp(
                    sharedImageUri = sharedImageUri.value,
                    onSharedImageHandled = { sharedImageUri.value = null },
                )
            }
        }
    }

    // singleTask means a share that arrives while we're already running comes through here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractSharedImage(intent)?.let { sharedImageUri.value = it }
    }
}

/** Pulls a single shared image URI out of an ACTION_SEND intent, or null if it isn't one. */
private fun extractSharedImage(intent: Intent?): Uri? {
    if (intent?.action != Intent.ACTION_SEND) return null
    if (intent.type?.startsWith("image/") != true) return null
    return IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
}

private sealed interface Screen {
    data object Home : Screen
    data class Preview(val file: File) : Screen
    data class Crop(val file: File) : Screen
    data class Lasso(val file: File) : Screen
    data class Detail(val fileName: String) : Screen
    data class Viewer(val file: File) : Screen
}

/**
 * Saves the navigation [Screen] across activity recreation / process death (e.g. rotating to take a
 * landscape photo, or the camera evicting our process), so we don't lose the Preview/Crop step and
 * silently send the capture straight to analysis. Encoded as [type, payload].
 */
private val ScreenSaver: Saver<Screen, List<String>> = Saver(
    save = { s ->
        when (s) {
            Screen.Home -> listOf("home")
            is Screen.Preview -> listOf("preview", s.file.absolutePath)
            is Screen.Crop -> listOf("crop", s.file.absolutePath)
            is Screen.Lasso -> listOf("lasso", s.file.absolutePath)
            is Screen.Detail -> listOf("detail", s.fileName)
            is Screen.Viewer -> listOf("viewer", s.file.absolutePath)
        }
    },
    restore = { l ->
        when (l[0]) {
            "preview" -> Screen.Preview(File(l[1]))
            "crop" -> Screen.Crop(File(l[1]))
            "lasso" -> Screen.Lasso(File(l[1]))
            "detail" -> Screen.Detail(l[1])
            "viewer" -> Screen.Viewer(File(l[1]))
            else -> Screen.Home
        }
    },
)

/** Saves a nullable [File] (the pending camera target) as its path. */
private val FileSaver: Saver<File?, String> = Saver(
    save = { it?.absolutePath ?: "" },
    restore = { if (it.isEmpty()) null else File(it) },
)

@Composable
fun ClipperApp(
    sharedImageUri: Uri? = null,
    onSharedImageHandled: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vm: ClippingsViewModel = viewModel()
    val clippings by vm.clippings.collectAsState()
    val userEmail by vm.authEmail.collectAsState()
    val userName by vm.authName.collectAsState()
    val authError by vm.authError.collectAsState()

    // Surface sign-in failures (otherwise they're invisible).
    LaunchedEffect(authError) {
        authError?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.clearAuthError()
        }
    }
    var pendingFile by rememberSaveable(stateSaver = FileSaver) { mutableStateOf<File?>(null) }
    var screen: Screen by rememberSaveable(stateSaver = ScreenSaver) {
        mutableStateOf(Screen.Home)
    }

    // Whenever we land back on the library, reconcile the DB with disk and analyze new clippings.
    // This covers every "saved" path: capture-back, crop-back, and lasso-Done.
    LaunchedEffect(screen) {
        if (screen is Screen.Home) vm.refresh()
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val captured = pendingFile
        pendingFile = null
        if (success && captured != null) {
            screen = Screen.Preview(captured)
        } else {
            captured?.delete()
        }
    }

    fun startCapture() {
        val (file, uri) = newClippingTarget(context)
        pendingFile = file
        cameraLauncher.launch(uri)
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startCapture() }

    // Imports an image from a content URI (gallery pick or share) into a clipping file, then routes
    // to Preview so it can be cropped/lassoed and analyzed exactly like a camera capture.
    fun beginClippingFrom(uri: Uri) {
        scope.launch {
            val file = withContext(Dispatchers.IO) { importImageToClipping(context, uri) }
            if (file != null) {
                screen = Screen.Preview(file)
            } else {
                Toast.makeText(context, "Couldn't open that image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) beginClippingFrom(uri) }

    // An image shared in from another app: import and preview it (once per new share).
    LaunchedEffect(sharedImageUri) {
        val uri = sharedImageUri ?: return@LaunchedEffect
        beginClippingFrom(uri)
        onSharedImageHandled()
    }

    when (val s = screen) {
        Screen.Home -> HomeScreen(
            clippings = clippings,
            userEmail = userEmail,
            userName = userName,
            onTakePhoto = {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) startCapture()
                else permLauncher.launch(Manifest.permission.CAMERA)
            },
            onPickPhoto = {
                galleryLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onOpen = { clipping -> screen = Screen.Detail(clipping.file.name) },
            onDelete = { toDelete -> vm.delete(toDelete) },
            onExport = { uri ->
                vm.export(uri) { ok ->
                    Toast.makeText(
                        context,
                        if (ok) "Export saved" else "Export failed",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            signInIntentProvider = { vm.signInIntent() },
            onSignInResult = { data -> vm.handleSignInResult(data) },
            onSignOut = { vm.signOut() },
            onClearAll = { vm.clearAll() },
            onSendFeedback = { msg ->
                vm.sendFeedback(msg) { ok ->
                    Toast.makeText(
                        context,
                        if (ok) "Feedback sent — thank you!" else "Couldn't send feedback",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
        )
        is Screen.Preview -> {
            BackHandler { screen = Screen.Home }
            PreviewScreen(
                file = s.file,
                onCrop = { screen = Screen.Crop(s.file) },
                onSelect = { screen = Screen.Lasso(s.file) },
            )
        }
        is Screen.Crop -> {
            BackHandler { screen = Screen.Preview(s.file) }
            CropScreen(
                file = s.file,
                onCancel = { screen = Screen.Preview(s.file) },
                onCropped = { screen = Screen.Home },
            )
        }
        is Screen.Lasso -> {
            BackHandler { screen = Screen.Preview(s.file) }
            LassoScreen(
                file = s.file,
                onCancel = { screen = Screen.Preview(s.file) },
                onSelected = { screen = Screen.Home },
            )
        }
        is Screen.Detail -> {
            val clipping = clippings.firstOrNull { it.file.name == s.fileName }
            // If the clipping vanished (e.g. deleted), fall back to the library.
            if (clipping == null) {
                LaunchedEffect(s.fileName) { screen = Screen.Home }
            } else {
                val allTags by vm.tags.collectAsState()
                val assignedTags by remember(s.fileName) { vm.tagsFor(s.fileName) }
                    .collectAsState(initial = emptyList())
                val comments by remember(s.fileName) { vm.commentsFor(s.fileName) }
                    .collectAsState(initial = emptyList())
                BackHandler { screen = Screen.Home }
                DetailScreen(
                    clipping = clipping,
                    allTags = allTags,
                    assignedTagIds = assignedTags.map { it.id }.toSet(),
                    comments = comments,
                    onBack = { screen = Screen.Home },
                    onRetry = { vm.retry(clipping.file.name) },
                    onToggleTag = { tag, assigned -> vm.setTag(s.fileName, tag.id, assigned) },
                    onCreateTag = { name -> vm.createTag(s.fileName, name) },
                    onAddComment = { text -> vm.addComment(s.fileName, text) },
                    onDeleteComment = { id -> vm.deleteComment(id) },
                    onOpenImage = { screen = Screen.Viewer(clipping.file) },
                )
            }
        }
        is Screen.Viewer -> {
            BackHandler { screen = Screen.Detail(s.file.name) }
            ImageViewerScreen(file = s.file, onBack = { screen = Screen.Detail(s.file.name) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@VisibleForTesting
@Composable
internal fun HomeScreen(
    clippings: List<Clipping>,
    userEmail: String?,
    userName: String?,
    onTakePhoto: () -> Unit,
    onPickPhoto: () -> Unit = {},
    onOpen: (Clipping) -> Unit,
    onDelete: (List<File>) -> Unit,
    onExport: (Uri) -> Unit,
    signInIntentProvider: () -> Intent?,
    onSignInResult: (Intent?) -> Unit,
    onSignOut: () -> Unit,
    onClearAll: () -> Unit,
    onSendFeedback: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> if (uri != null) onExport(uri) }
    val signInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result -> onSignInResult(result.data) }

    var selected by remember { mutableStateOf(emptySet<File>()) }
    val inSelectionMode = selected.isNotEmpty()

    var query by remember { mutableStateOf("") }
    var sortDescending by remember { mutableStateOf(true) }
    var showFilter by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showFeedback by remember { mutableStateOf(false) }
    var feedbackText by remember { mutableStateOf("") }

    // While selecting, Back clears the selection instead of leaving the screen.
    BackHandler(enabled = inSelectionMode) { selected = emptySet() }

    // Apply the search query (matches summary / extracted text / file name) and the chosen sort.
    val visible = clippings
        .filter { c ->
            val q = query.trim()
            q.isEmpty() ||
                c.summary?.contains(q, ignoreCase = true) == true ||
                c.extractedText?.contains(q, ignoreCase = true) == true ||
                c.file.name.contains(q, ignoreCase = true)
        }
        .sortedBy { it.createdAt }
        .let { if (sortDescending) it.reversed() else it }

    if (showFilter) {
        FilterDialog(
            sortDescending = sortDescending,
            onApply = { descending ->
                sortDescending = descending
                showFilter = false
            },
            onDismiss = { showFilter = false },
        )
    }

    if (showFeedback) {
        AlertDialog(
            onDismissRequest = { showFeedback = false },
            title = { Text("Give feedback") },
            text = {
                OutlinedTextField(
                    value = feedbackText,
                    onValueChange = { feedbackText = it },
                    placeholder = { Text("What's working, what's broken, ideas…") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = feedbackText.isNotBlank(),
                    onClick = {
                        onSendFeedback(feedbackText)
                        feedbackText = ""
                        showFeedback = false
                    },
                ) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { showFeedback = false }) { Text("Cancel") } },
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear all data?") },
            text = {
                Text("This permanently deletes every clipping, tag and comment from this device. This can't be undone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    selected = emptySet()
                    onClearAll()
                }) { Text("Delete everything") }
            },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") } },
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    "Paper Clipper AI",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(16.dp),
                )
                if (userEmail != null) {
                    Text(
                        "Logged in as ${userName ?: userEmail}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 16.dp, bottom = 12.dp),
                    )
                }
                HorizontalDivider()

                if (userEmail == null) {
                    NavigationDrawerItem(
                        label = { Text("Log in") },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            val intent = signInIntentProvider()
                            if (intent != null) {
                                signInLauncher.launch(intent)
                            } else {
                                Toast.makeText(
                                    context,
                                    "Sign-in isn't set up yet — add Firebase config.",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                NavigationDrawerItem(
                    label = { Text("Export") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        exportLauncher.launch("paper-clippings.zip")
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                NavigationDrawerItem(
                    label = { Text("Clear all", color = MaterialTheme.colorScheme.error) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        showClearConfirm = true
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )

                Spacer(Modifier.weight(1f))

                // Bottom-pinned actions: Log out (if logged in) then Give feedback at the very bottom.
                HorizontalDivider()
                if (userEmail != null) {
                    NavigationDrawerItem(
                        label = { Text("Log out") },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            onSignOut()
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                NavigationDrawerItem(
                    label = { Text("Give feedback") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        showFeedback = true
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        },
    ) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            if (inSelectionMode) {
                TopAppBar(
                    title = { Text("${selected.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { selected = emptySet() }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_close),
                                contentDescription = "Clear selection",
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                val toDelete = selected.toList()
                                selected = emptySet()
                                onDelete(toDelete)
                            },
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete),
                                contentDescription = "Delete selected",
                            )
                        }
                    },
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_menu),
                            contentDescription = "Open menu",
                        )
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("Search clippings") },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_search),
                                contentDescription = null,
                            )
                        },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_close),
                                        contentDescription = "Clear search",
                                    )
                                }
                            }
                        },
                    )
                    IconButton(onClick = { showFilter = true }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_filter),
                            contentDescription = "Filter",
                        )
                    }
                }
            }
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onTakePhoto,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Take a photo")
                }
                OutlinedButton(
                    onClick = onPickPhoto,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Choose photo")
                }
            }
        },
    ) { padding ->
        if (visible.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (clippings.isEmpty()) {
                        "No clippings yet — tap Take a photo below."
                    } else {
                        "No clippings match your search."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                dateSections(visible).forEach { (header, sectionItems) ->
                    stickyHeader(key = "header:$header") { DateHeader(header) }
                    items(items = sectionItems, key = { it.file.absolutePath }) { clipping ->
                    val file = clipping.file
                    val isSelected = file in selected
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .combinedClickable(
                                onClick = {
                                    if (inSelectionMode) {
                                        selected =
                                            if (isSelected) selected - file else selected + file
                                    } else {
                                        onOpen(clipping)
                                    }
                                },
                                onLongClick = { selected = selected + file },
                            ),
                    ) {
                        AsyncImage(
                            model = file,
                            contentDescription = "Saved clipping",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        // Caption over a bottom scrim. While searching, show the matching excerpt
                        // with the query highlighted (Preview-style); otherwise status + summary.
                        val q = query.trim()
                        val matchField = if (q.isEmpty()) {
                            null
                        } else {
                            clipping.extractedText?.takeIf { it.contains(q, ignoreCase = true) }
                                ?: clipping.summary?.takeIf { it.contains(q, ignoreCase = true) }
                        }
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.45f))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            if (matchField != null) {
                                Text(
                                    text = searchSnippet(matchField, q),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            } else {
                                // For an analyzed clipping show its AI heading as the label; fall
                                // back to the status word ("Summary"/"Analyzing…"/"Analysis failed").
                                val label = clipping.heading
                                    ?.takeIf { it.isNotBlank() && clipping.status == ClippingStatus.SUCCESS }
                                    ?: statusLabel(clipping.status)
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White,
                                )
                                val caption = clipping.summary ?: clipping.errorMessage
                                if (!caption.isNullOrBlank()) {
                                    Text(
                                        text = caption,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        if (inSelectionMode) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.35f)),
                            ) {
                                Checkbox(checked = isSelected, onCheckedChange = null)
                            }
                        }
                    }
                    }
                }
            }
        }
    }
    }
}

/** Filter sheet: currently a "Sort by" date direction, applied on confirm. */
@VisibleForTesting
@Composable
internal fun FilterDialog(
    sortDescending: Boolean,
    onApply: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var descending by remember { mutableStateOf(sortDescending) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter") },
        text = {
            Column {
                Text("Sort by", style = MaterialTheme.typography.titleSmall)
                SortOption("Date — newest first", selected = descending) { descending = true }
                SortOption("Date — oldest first", selected = !descending) { descending = false }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(descending) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SortOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

/**
 * Builds a short excerpt of [source] centered on the first occurrence of [query], with every
 * occurrence of the query highlighted (bold + amber) — like the macOS Preview search results.
 */
@VisibleForTesting
internal fun searchSnippet(source: String, query: String): AnnotatedString {
    val radius = 60
    val first = source.indexOf(query, ignoreCase = true).coerceAtLeast(0)
    val start = (first - radius).coerceAtLeast(0)
    val end = (first + query.length + radius).coerceAtMost(source.length)
    val prefix = if (start > 0) "…" else ""
    val suffix = if (end < source.length) "…" else ""
    val window = prefix + source.substring(start, end).replace('\n', ' ').trim() + suffix

    return buildAnnotatedString {
        append(window)
        var i = window.indexOf(query, ignoreCase = true)
        while (i >= 0) {
            addStyle(
                SpanStyle(fontWeight = FontWeight.Bold, color = Color(0xFFFFE082)),
                i,
                i + query.length,
            )
            i = window.indexOf(query, startIndex = i + query.length, ignoreCase = true)
        }
    }
}

/** Full-screen image viewer with pinch-to-zoom, pan (clamped to bounds), and double-tap to zoom. */
@Composable
private fun ImageViewerScreen(file: File, onBack: () -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()
        AsyncImage(
            model = file,
            contentDescription = "Full clipping",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, 5f)
                        val maxX = maxW * (newScale - 1f) / 2f
                        val maxY = maxH * (newScale - 1f) / 2f
                        val next = if (newScale > 1f) offset + pan else Offset.Zero
                        scale = newScale
                        offset = Offset(
                            next.x.coerceIn(-maxX, maxX),
                            next.y.coerceIn(-maxY, maxY),
                        )
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.5f
                            }
                        },
                    )
                },
        )
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = "Close",
                tint = Color.White,
            )
        }
    }
}

/**
 * Groups an already-sorted clipping list into date sections (Google Photos style): a month with a
 * single clipping becomes one "June 2026" header; a month with several is split into per-day
 * headers ("7 June 2026"). Order is preserved, so it follows the chosen newest/oldest sort.
 */
@VisibleForTesting
internal fun dateSections(visible: List<Clipping>): List<Pair<String, List<Clipping>>> {
    val byMonth = LinkedHashMap<String, MutableList<Clipping>>()
    for (c in visible) byMonth.getOrPut(fmt("yyyy-MM", c.createdAt)) { mutableListOf() }.add(c)

    val out = mutableListOf<Pair<String, List<Clipping>>>()
    for (monthItems in byMonth.values) {
        if (monthItems.size == 1) {
            out += fmt("MMMM yyyy", monthItems[0].createdAt) to monthItems
        } else {
            val byDay = LinkedHashMap<String, MutableList<Clipping>>()
            for (c in monthItems) byDay.getOrPut(fmt("yyyy-MM-dd", c.createdAt)) { mutableListOf() }.add(c)
            for (dayItems in byDay.values) {
                out += fmt("d MMMM yyyy", dayItems[0].createdAt) to dayItems
            }
        }
    }
    return out
}

@VisibleForTesting
internal fun fmt(pattern: String, time: Long): String =
    SimpleDateFormat(pattern, Locale.getDefault()).format(Date(time))

@Composable
private fun DateHeader(text: String) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(vertical = 6.dp),
        )
    }
}

@VisibleForTesting
internal fun statusLabel(status: ClippingStatus): String = when (status) {
    ClippingStatus.PENDING, ClippingStatus.PROCESSING -> "Analyzing…"
    ClippingStatus.SUCCESS -> "Summary"
    ClippingStatus.ERROR -> "Analysis failed"
}

/** Full-screen view of a clipping with its Gemini-extracted text, summary, tags and comments. */
@VisibleForTesting
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailScreen(
    clipping: Clipping,
    allTags: List<TagEntity>,
    assignedTagIds: Set<Long>,
    comments: List<CommentEntity>,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onToggleTag: (TagEntity, Boolean) -> Unit,
    onCreateTag: (String) -> Unit,
    onAddComment: (String) -> Unit,
    onDeleteComment: (Long) -> Unit,
    onOpenImage: () -> Unit,
) {
    val context = LocalContext.current
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Clipping") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { shareClipping(context, clipping) }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_share),
                            contentDescription = "Share",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AsyncImage(
                model = clipping.file,
                contentDescription = "Clipping (tap to zoom)",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenImage),
            )
            Text(
                "Tap the image to view full screen and zoom",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (clipping.status) {
                ClippingStatus.PENDING, ClippingStatus.PROCESSING -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.height(20.dp))
                        Text("Analyzing with Gemini…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                ClippingStatus.ERROR -> {
                    Text("Analysis failed", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = clipping.errorMessage ?: "Unknown error",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onRetry) { Text("Retry") }
                }
                ClippingStatus.SUCCESS -> {
                    if (!clipping.summary.isNullOrBlank()) {
                        Text(
                            clipping.heading?.takeIf { it.isNotBlank() } ?: "Summary",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        SelectionContainer {
                            Text(clipping.summary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (!clipping.extractedText.isNullOrBlank()) {
                        Text("Article", style = MaterialTheme.typography.titleMedium)
                        SelectionContainer {
                            Text(clipping.extractedText, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            HorizontalDivider()
            TagsSection(
                allTags = allTags,
                assignedTagIds = assignedTagIds,
                onToggleTag = onToggleTag,
                onCreateTag = onCreateTag,
            )

            HorizontalDivider()
            CommentsSection(
                comments = comments,
                onAddComment = onAddComment,
                onDeleteComment = onDeleteComment,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsSection(
    allTags: List<TagEntity>,
    assignedTagIds: Set<Long>,
    onToggleTag: (TagEntity, Boolean) -> Unit,
    onCreateTag: (String) -> Unit,
) {
    var newTag by remember { mutableStateOf("") }

    Text("Tags", style = MaterialTheme.typography.titleMedium)
    Text(
        "Tags are shared across all clippings — tap to add or remove for this one.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (allTags.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            allTags.forEach { tag ->
                val selected = tag.id in assignedTagIds
                FilterChip(
                    selected = selected,
                    onClick = { onToggleTag(tag, !selected) },
                    label = { Text(tag.name) },
                    leadingIcon = if (selected) {
                        {
                            Icon(
                                painter = painterResource(R.drawable.ic_check),
                                contentDescription = null,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = newTag,
            onValueChange = { newTag = it },
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("New tag") },
        )
        Button(
            onClick = {
                onCreateTag(newTag)
                newTag = ""
            },
            enabled = newTag.isNotBlank(),
        ) { Text("Add") }
    }
}

@Composable
private fun CommentsSection(
    comments: List<CommentEntity>,
    onAddComment: (String) -> Unit,
    onDeleteComment: (Long) -> Unit,
) {
    var newComment by remember { mutableStateOf("") }

    Text("Comments", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = newComment,
            onValueChange = { newComment = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Add a comment") },
        )
        Button(
            onClick = {
                onAddComment(newComment)
                newComment = ""
            },
            enabled = newComment.isNotBlank(),
        ) { Text("Add") }
    }
    comments.forEach { comment ->
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(comment.text, style = MaterialTheme.typography.bodyMedium)
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(comment.createdAt)),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            IconButton(onClick = { onDeleteComment(comment.id) }) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = "Delete comment",
                )
            }
        }
    }
}

@Composable
private fun PreviewScreen(
    file: File,
    onCrop: () -> Unit,
    onSelect: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AsyncImage(
            model = file,
            contentDescription = "Captured clipping",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Button(onClick = onCrop) { Text("Crop") }
            Button(onClick = onSelect) { Text("Select") }
        }
    }
}

@Composable
private fun CropScreen(
    file: File,
    onCancel: () -> Unit,
    onCropped: (File) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberCropifyState()

    // Cropify decodes the JPEG with BitmapFactory, which ignores the EXIF orientation
    // tag — so handing it the raw file shows (and then saves) camera photos rotated
    // relative to the Preview screen, where Coil honours EXIF. Decode an already-upright
    // bitmap ourselves (decodeSampledBitmap applies the EXIF rotation) and feed Cropify's
    // in-memory overload, mirroring how the Lasso screen solves the same problem.
    val image by produceState<ImageBitmap?>(initialValue = null, file) {
        value = withContext(Dispatchers.IO) { decodeSampledBitmap(file, 2048)?.asImageBitmap() }
        if (value == null) onCancel()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Button(onClick = onCancel) { Text("Cancel") }
            Button(onClick = { if (image != null) state.crop() }) { Text("Done") }
        }
        // The crop area is given its own region below the buttons and inset from
        // the screen edges so all four corner handles stay reachable to drag-resize.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            val img = image
            if (img == null) {
                CircularProgressIndicator(color = Color.White)
            } else {
                Cropify(
                    bitmap = img,
                    state = state,
                    onImageCropped = { cropped: ImageBitmap ->
                        scope.launch {
                            val out = saveCrop(context, cropped)
                            file.delete()
                            onCropped(out)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private suspend fun saveCrop(context: Context, image: ImageBitmap): File =
    withContext(Dispatchers.IO) {
        val out = File(clippingsDir(context), "clipping_${System.currentTimeMillis()}.jpg")
        out.outputStream().use { stream ->
            image.asAndroidBitmap().compress(Bitmap.CompressFormat.JPEG, 95, stream)
        }
        out
    }

/**
 * Freeform "lasso" selection. The user drags a closed path over the image; the region
 * inside the path is kept and everything outside is made transparent. Mirrors [CropScreen]'s
 * layout (Cancel / Done above an inset image area) so the two flows feel consistent.
 */
@Composable
private fun LassoScreen(
    file: File,
    onCancel: () -> Unit,
    onSelected: (File) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val points = remember { mutableStateListOf<Offset>() }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    val image by produceState<ImageBitmap?>(initialValue = null, file) {
        value = withContext(Dispatchers.IO) { decodeSampledBitmap(file, 2048)?.asImageBitmap() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Button(onClick = onCancel) { Text("Cancel") }
            Button(
                onClick = {
                    val img = image ?: return@Button
                    if (points.size < 3 || canvasSize == IntSize.Zero) return@Button
                    scope.launch {
                        val out = saveLasso(context, img, points.toList(), canvasSize)
                        if (out != null) {
                            file.delete()
                            onSelected(out)
                        }
                    }
                },
            ) { Text("Done") }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            val img = image
            if (img == null) {
                CircularProgressIndicator(color = Color.White)
            } else {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { canvasSize = it }
                        .pointerInput(img) {
                            detectDragGestures(
                                onDragStart = { start ->
                                    points.clear()
                                    points.add(start)
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    points.add(change.position)
                                },
                            )
                        },
                ) {
                    val scale = min(size.width / img.width, size.height / img.height)
                    val drawnW = img.width * scale
                    val drawnH = img.height * scale
                    val offX = (size.width - drawnW) / 2f
                    val offY = (size.height - drawnH) / 2f
                    drawImage(
                        image = img,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(img.width, img.height),
                        dstOffset = IntOffset(offX.roundToInt(), offY.roundToInt()),
                        dstSize = IntSize(drawnW.roundToInt(), drawnH.roundToInt()),
                    )
                    if (points.isNotEmpty()) {
                        val path = Path().apply {
                            moveTo(points.first().x, points.first().y)
                            for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
                            close()
                        }
                        drawPath(path = path, color = Color.White.copy(alpha = 0.25f))
                        drawPath(
                            path = path,
                            color = Color.White,
                            style = Stroke(
                                width = 3.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** Masks [image] to the freeform [canvasPoints] (in canvas pixels) and saves the cropped result as PNG. */
private suspend fun saveLasso(
    context: Context,
    image: ImageBitmap,
    canvasPoints: List<Offset>,
    canvasSize: IntSize,
): File? = withContext(Dispatchers.IO) {
    val src = image.asAndroidBitmap()
    val bw = src.width.toFloat()
    val bh = src.height.toFloat()
    // Same fit math as the Canvas, to map canvas coordinates back to bitmap pixels.
    val scale = min(canvasSize.width / bw, canvasSize.height / bh)
    val offX = (canvasSize.width - bw * scale) / 2f
    val offY = (canvasSize.height - bh * scale) / 2f

    val path = android.graphics.Path()
    canvasPoints.forEachIndexed { i, p ->
        val x = ((p.x - offX) / scale).coerceIn(0f, bw)
        val y = ((p.y - offY) / scale).coerceIn(0f, bh)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()

    val bounds = RectF()
    path.computeBounds(bounds, true)
    val left = bounds.left.toInt().coerceIn(0, src.width)
    val top = bounds.top.toInt().coerceIn(0, src.height)
    val width = bounds.width().roundToInt().coerceIn(1, src.width - left)
    val height = bounds.height().roundToInt().coerceIn(1, src.height - top)

    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(out).apply {
        translate(-left.toFloat(), -top.toFloat())
        clipPath(path)
        drawBitmap(src, 0f, 0f, null)
    }

    val file = File(clippingsDir(context), "clipping_${System.currentTimeMillis()}.png")
    file.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    file
}

/** Decodes [file] downsampled so its longest edge is at most [maxDim] px, to bound memory. */
private fun decodeSampledBitmap(file: File, maxDim: Int): Bitmap? {
    val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, probe)
    var sample = 1
    val longest = maxOf(probe.outWidth, probe.outHeight)
    while (longest / sample > maxDim) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
    // BitmapFactory ignores the JPEG EXIF orientation tag (Coil applies it on the Preview screen),
    // so apply it here too — otherwise camera photos appear rotated/flipped in the lasso screen.
    return applyExifOrientation(file, bitmap)
}

private fun applyExifOrientation(file: File, bitmap: Bitmap): Bitmap {
    val orientation = runCatching {
        ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
        else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

/** Shares the clipping image plus its summary text to other apps via the system share sheet. */
private fun shareClipping(context: Context, clipping: Clipping) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        clipping.file,
    )
    val text = clipping.summary?.takeIf { it.isNotBlank() }
        ?: clipping.extractedText?.takeIf { it.isNotBlank() }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mimeTypeFor(clipping.file)
        putExtra(Intent.EXTRA_STREAM, uri)
        if (text != null) putExtra(Intent.EXTRA_TEXT, text)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share clipping"))
}

/**
 * Copies an image referenced by a content [uri] (from the photo picker or a share intent) into a
 * new clipping file on disk. Returns the file, or null if the image couldn't be read. Bytes are
 * copied verbatim so any JPEG EXIF orientation is preserved for the Preview/Crop/Lasso screens.
 */
private fun importImageToClipping(context: Context, uri: Uri): File? = runCatching {
    val isPng = context.contentResolver.getType(uri)?.contains("png", ignoreCase = true) == true
    val ext = if (isPng) "png" else "jpg"
    val file = File(clippingsDir(context), "clipping_${System.currentTimeMillis()}.$ext")
    context.contentResolver.openInputStream(uri)?.use { input ->
        file.outputStream().use { input.copyTo(it) }
    } ?: return@runCatching null
    file
}.getOrNull()

private fun newClippingTarget(context: Context): Pair<File, Uri> {
    val file = File(clippingsDir(context), "clipping_${System.currentTimeMillis()}.jpg")
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    return file to uri
}
