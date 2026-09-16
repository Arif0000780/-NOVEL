package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.ui.components.AppNavDestination
import com.example.ui.components.BottomNavBar
import com.example.ui.components.TtsMiniPlayer
import com.example.ui.screens.*
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.*
import kotlinx.coroutines.launch

sealed class Screen {
    data object MainTabs : Screen()
    data class UrlImport(val initialUrl: String = "") : Screen()
    data class NovelChapters(val novelId: Long) : Screen()
    data class Reader(val chapterId: Long) : Screen()
    data object TtsPlayer : Screen()
    data object NormalTranslator : Screen()
}

class MainActivity : ComponentActivity() {

    private lateinit var novelViewModel: NovelViewModel
    private lateinit var readerViewModel: ReaderViewModel
    private lateinit var ttsViewModel: TtsViewModel
    private lateinit var settingsViewModel: SettingsViewModel
    private lateinit var normalTranslatorViewModel: NormalTranslatorViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as NovelReaderApplication
        novelViewModel = NovelViewModel(app.novelRepository, app.parserManager)
        readerViewModel = ReaderViewModel(app.novelRepository, app.settingsRepository, app.translationManager, app.parserManager)
        ttsViewModel = TtsViewModel(app.ttsPlaybackManager, app.novelRepository, app.settingsRepository)
        settingsViewModel = SettingsViewModel(app.settingsRepository, app.novelRepository)
        normalTranslatorViewModel = NormalTranslatorViewModel()

        // Wire Auto Next Chapter callback
        app.ttsPlaybackManager.onAutoNextChapterRequested = {
            lifecycleScope.launch {
                val settings = app.settingsRepository.getSettings()
                if (settings.autoNextChapter) {
                    val currentChapterId = app.ttsPlaybackManager.chapterId.value
                    if (currentChapterId != null) {
                        val currentChapter = app.novelRepository.getChapterById(currentChapterId)
                        if (currentChapter != null) {
                            val nextChapterInDb = app.novelRepository.getChaptersListForNovel(currentChapter.novelId)
                                .firstOrNull { it.chapterNumber == currentChapter.chapterNumber + 1 }

                            if (nextChapterInDb != null) {
                                val novel = app.novelRepository.getNovelById(nextChapterInDb.novelId)
                                val translation = app.novelRepository.getTranslationForChapter(nextChapterInDb.id)
                                val textToSpeak = translation?.translatedContent ?: nextChapterInDb.content
                                if (novel != null && textToSpeak.isNotBlank()) {
                                    app.ttsPlaybackManager.prepareChapter(
                                        novelTitle = novel.title,
                                        chapterTitle = nextChapterInDb.title,
                                        chapterId = nextChapterInDb.id,
                                        text = textToSpeak,
                                        startParagraph = 0
                                    )
                                    app.ttsPlaybackManager.play()
                                }
                            }
                        }
                    }
                }
            }
        }

        setContent {
            MyApplicationTheme {
                MainAppContent(
                    novelViewModel = novelViewModel,
                    readerViewModel = readerViewModel,
                    ttsViewModel = ttsViewModel,
                    settingsViewModel = settingsViewModel,
                    normalTranslatorViewModel = normalTranslatorViewModel
                )
            }
        }
    }
}

@Composable
fun MainAppContent(
    novelViewModel: NovelViewModel,
    readerViewModel: ReaderViewModel,
    ttsViewModel: TtsViewModel,
    settingsViewModel: SettingsViewModel,
    normalTranslatorViewModel: NormalTranslatorViewModel
) {
    var currentTab by remember { mutableStateOf(AppNavDestination.HOME) }
    var currentScreen by remember { mutableStateOf<Screen>(Screen.MainTabs) }

    val isTtsPlaying by ttsViewModel.isPlaying.collectAsState()
    val isTtsPaused by ttsViewModel.isPaused.collectAsState()
    val novelTitle by ttsViewModel.novelTitle.collectAsState()
    val chapterTitle by ttsViewModel.chapterTitle.collectAsState()
    val currentPara by ttsViewModel.currentParagraph.collectAsState()
    val totalParas by ttsViewModel.totalParagraphs.collectAsState()
    val speed by ttsViewModel.speed.collectAsState()

    // Back handling
    BackHandler(enabled = currentScreen !is Screen.MainTabs || currentTab != AppNavDestination.HOME) {
        if (currentScreen !is Screen.MainTabs) {
            currentScreen = Screen.MainTabs
        } else if (currentTab != AppNavDestination.HOME) {
            currentTab = AppNavDestination.HOME
        }
    }

    Scaffold(
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Mini Player (Only show if not already inside full TtsPlayerScreen or ReaderScreen)
                if (currentScreen !is Screen.TtsPlayer) {
                    TtsMiniPlayer(
                        isPlaying = isTtsPlaying,
                        isPaused = isTtsPaused,
                        novelTitle = novelTitle,
                        chapterTitle = chapterTitle,
                        currentPara = currentPara,
                        totalParas = totalParas,
                        speed = speed,
                        onPlayPause = {
                            if (isTtsPlaying) ttsViewModel.pause() else ttsViewModel.resume()
                        },
                        onNext = { ttsViewModel.nextParagraph() },
                        onClose = { ttsViewModel.stop() },
                        onExpand = { currentScreen = Screen.TtsPlayer }
                    )
                }

                // Bottom Navigation Bar only shown on main tabs
                if (currentScreen is Screen.MainTabs) {
                    BottomNavBar(
                        currentDestination = currentTab,
                        onNavigate = { currentTab = it }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (val screen = currentScreen) {
                is Screen.MainTabs -> {
                    when (currentTab) {
                        AppNavDestination.HOME -> HomeScreen(
                            novelViewModel = novelViewModel,
                            onOpenUrlImport = { url -> currentScreen = Screen.UrlImport(url) },
                            onOpenNovelChapters = { novelId -> currentScreen = Screen.NovelChapters(novelId) },
                            onContinueReading = { novelId, chapterNum ->
                                val novel = novelViewModel.allNovels.value.firstOrNull { it.id == novelId }
                                if (novel != null) {
                                    currentScreen = Screen.NovelChapters(novelId)
                                }
                            },
                            onQuickTtsPlay = { novelId ->
                                currentScreen = Screen.NovelChapters(novelId)
                            },
                            onOpenTranslator = {
                                currentTab = AppNavDestination.TRANSLATOR
                            }
                        )

                        AppNavDestination.TRANSLATOR -> NormalTranslatorScreen(
                            onNavigateBack = { currentTab = AppNavDestination.HOME }
                        )

                        AppNavDestination.LIBRARY -> LibraryScreen(
                            novelViewModel = novelViewModel,
                            onOpenNovel = { novelId -> currentScreen = Screen.NovelChapters(novelId) },
                            onContinueReading = { novelId, _ ->
                                currentScreen = Screen.NovelChapters(novelId)
                            }
                        )

                        AppNavDestination.HISTORY -> HistoryScreen(
                            novelViewModel = novelViewModel,
                            onOpenNovel = { novelId -> currentScreen = Screen.NovelChapters(novelId) },
                            onContinueReading = { novelId, _ ->
                                currentScreen = Screen.NovelChapters(novelId)
                            }
                        )

                        AppNavDestination.SETTINGS -> SettingsScreen(
                            settingsViewModel = settingsViewModel
                        )
                    }
                }

                is Screen.UrlImport -> UrlImportScreen(
                    novelViewModel = novelViewModel,
                    initialUrl = screen.initialUrl,
                    onNavigateBack = { currentScreen = Screen.MainTabs },
                    onChapterImported = { chapterId ->
                        currentScreen = Screen.Reader(chapterId)
                    }
                )

                is Screen.NovelChapters -> ChapterListScreen(
                    novelId = screen.novelId,
                    novelRepository = NovelReaderApplication.instance.novelRepository,
                    onNavigateBack = { currentScreen = Screen.MainTabs },
                    onReadChapter = { chapterId ->
                        currentScreen = Screen.Reader(chapterId)
                    },
                    onPlayTtsChapter = { chapterId ->
                        currentScreen = Screen.Reader(chapterId)
                    },
                    onAddChapterUrl = { url ->
                        currentScreen = Screen.UrlImport(url)
                    }
                )

                is Screen.Reader -> ReaderScreen(
                    chapterId = screen.chapterId,
                    readerViewModel = readerViewModel,
                    ttsViewModel = ttsViewModel,
                    onNavigateBack = { currentScreen = Screen.MainTabs },
                    onOpenTtsPlayer = { currentScreen = Screen.TtsPlayer }
                )

                is Screen.TtsPlayer -> TtsPlayerScreen(
                    ttsViewModel = ttsViewModel,
                    onNavigateBack = { currentScreen = Screen.MainTabs }
                )

                is Screen.NormalTranslator -> NormalTranslatorScreen(
                    onNavigateBack = { currentScreen = Screen.MainTabs }
                )
            }
        }
    }
}
