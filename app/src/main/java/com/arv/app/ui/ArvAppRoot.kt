package com.arv.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Park
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.arv.app.R
import com.arv.app.core.session.ActiveSession
import com.arv.app.feature.documents.AddDocumentScreen
import com.arv.app.feature.documents.DocumentsScreen
import com.arv.app.feature.feed.FeedScreen
import com.arv.app.feature.librarian.LibrarianScreen
import com.arv.app.feature.onboarding.OnboardingScreen
import com.arv.app.feature.people.AddPersonScreen
import com.arv.app.feature.people.PeopleScreen
import com.arv.app.feature.people.PlacePeopleScreen
import com.arv.app.feature.people.PersonDetailScreen
import com.arv.app.feature.record.RecordScreen
import com.arv.app.feature.record.RecordingBus
import com.arv.app.feature.record.ReviewSaveScreen
import com.arv.app.feature.search.SearchScreen
import com.arv.app.feature.settings.SettingsScreen
import com.arv.app.feature.story.StoryDetailScreen
import com.arv.app.feature.timeline.TimelineScreen
import com.arv.app.ui.theme.ArvHero

sealed class Destination(val route: String) {
    /** Screen 01. Only reachable before an archive exists on this phone. */
    data object Onboarding : Destination("onboarding")

    /** Adding a relative by hand, reached from the Tree tab. */
    data object AddPerson : Destination("addPerson")

    /** Filing a certificate, photograph or letter, reached from Documents. */
    data object AddDocument : Destination("addDocument")

    /** Speech-model setup and the way out of an archive. */
    data object Settings : Destination("settings")

    /** The imported people the archive cannot yet place in the tree. */
    data object PlacePeople : Destination("placePeople")

    /** The private family feed. TODO(UX-3): the feed itself; today this shows the archive. */
    data object Family : Destination("family")
    data object Timeline : Destination("timeline")

    /** TODO(UX-6): the interactive tree; today this is the flat people list. */
    data object Tree : Destination("tree")

    /** Records rather than recordings, linked to the people they name. */
    data object Documents : Destination("documents")

    data object Librarian : Destination("librarian")

    /** Same retrieval as the librarian, without generation. Reached from the Librarian tab. */
    data object Search : Destination("search")
    data object Record : Destination("record")

    /** Screen 05. Reads the finished recording from [RecordingBus]. */
    data object ReviewSave : Destination("review")
    data object StoryDetail : Destination("story/{storyId}") {
        fun of(storyId: String) = "story/$storyId"
    }

    /** Screen 19. One person, their voice meter, and everything they touch in the archive. */
    data object PersonDetail : Destination("person/{personId}") {
        fun of(personId: String) = "person/$personId"
    }
}

private data class Tab(
    val destination: Destination,
    val icon: ImageVector,
    val labelRes: Int
)

// Order matches the comp: Home, Tree, [Record], Timeline, Librarian.
private val leftTabs = listOf(
    Tab(Destination.Family, Icons.Outlined.Home, R.string.tab_family),
    Tab(Destination.Tree, Icons.Outlined.Park, R.string.tab_tree)
)
private val rightTabs = listOf(
    Tab(Destination.Timeline, Icons.Outlined.Schedule, R.string.tab_timeline),
    Tab(Destination.Librarian, Icons.Outlined.MenuBook, R.string.tab_librarian)
)
private val tabs = leftTabs + rightTabs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArvAppRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val onATab = tabs.any { tab ->
        currentDestination?.hierarchy?.any { it.route == tab.destination.route } == true
    }

    com.arv.app.ui.theme.ArvBackground {
    Scaffold(
        // Transparent so the themed glow painted behind the app shows through.
        // Heirloom's ground is opaque paper, so it looks exactly as it always has.
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        // Stated because a transparent container cannot imply one, and the fallback is
        // black: every headline without an explicit color vanished on the dark themes.
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            // Every screen off the tabs gets a visible way back. The system gesture is
            // invisible, and a 78-year-old storyteller will never find it. Screen 04's
            // full-attention layout earns its own top bar; everything else shares this.
            val onOnboarding =
                currentDestination?.route == Destination.Onboarding.route
            if (!onATab && !onOnboarding) {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (onATab) {
                NavigationBar {
                    val tabItem: @Composable (Tab) -> Unit = { tab ->
                        val selected = currentDestination?.hierarchy
                            ?.any { it.route == tab.destination.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.labelRes)) }
                        )
                    }

                    leftTabs.forEach { tabItem(it) }

                    // Record lives in the middle of the bar, one tap from every tab,
                    // drawn as the terracotta circle from the comp. If it ever costs
                    // two taps, the design regressed.
                    NavigationBarItem(
                        selected = false,
                        onClick = { navController.navigate(Destination.Record.route) },
                        icon = {
                            Box(
                                Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(ArvHero.cta),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.Mic,
                                    contentDescription = stringResource(R.string.record_start),
                                    tint = ArvHero.on
                                )
                            }
                        },
                        label = { Text(stringResource(R.string.tab_record)) }
                    )

                    rightTabs.forEach { tabItem(it) }
                }
            }
        }
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding)) {
            NavHost(
                navController = navController,
                startDestination = if (ActiveSession.isSignedIn) {
                    Destination.Family.route
                } else {
                    Destination.Onboarding.route
                }
            ) {
                composable(Destination.Onboarding.route) {
                    OnboardingScreen(
                        onReady = {
                            navController.navigate(Destination.Family.route) {
                                popUpTo(Destination.Onboarding.route) { inclusive = true }
                            }
                        }
                    )
                }
                composable(Destination.AddPerson.route) {
                    AddPersonScreen(
                        onSaved = { personId ->
                            navController.navigate(Destination.PersonDetail.of(personId)) {
                                popUpTo(Destination.AddPerson.route) { inclusive = true }
                            }
                        }
                    )
                }
                composable(Destination.Family.route) {
                    FeedScreen(
                        onOpenStory = { navController.navigate(Destination.StoryDetail.of(it)) },
                        onOpenPerson = { navController.navigate(Destination.PersonDetail.of(it)) },
                        onRecord = { navController.navigate(Destination.Record.route) },
                        onOpenSettings = { navController.navigate(Destination.Settings.route) },
                        onViewAll = {
                            navController.navigate(Destination.Timeline.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
                composable(Destination.Timeline.route) {
                    TimelineScreen(
                        onOpenStory = { navController.navigate(Destination.StoryDetail.of(it)) },
                        onRecord = { navController.navigate(Destination.Record.route) }
                    )
                }
                composable(Destination.Tree.route) {
                    PeopleScreen(
                        onOpenDocuments = { navController.navigate(Destination.Documents.route) },
                        onOpenPerson = { navController.navigate(Destination.PersonDetail.of(it)) },
                        onAddPerson = { navController.navigate(Destination.AddPerson.route) },
                        onPlacePeople = { navController.navigate(Destination.PlacePeople.route) }
                    )
                }
                composable(
                    route = Destination.PersonDetail.route,
                    arguments = listOf(navArgument("personId") { type = NavType.StringType })
                ) {
                    PersonDetailScreen(
                        onOpenStory = { navController.navigate(Destination.StoryDetail.of(it)) },
                        onOpenPerson = { navController.navigate(Destination.PersonDetail.of(it)) }
                    )
                }
                composable(Destination.Documents.route) {
                    DocumentsScreen(
                        onOpenStory = { navController.navigate(Destination.StoryDetail.of(it)) },
                        onAddDocument = { navController.navigate(Destination.AddDocument.route) }
                    )
                }
                composable(Destination.PlacePeople.route) {
                    PlacePeopleScreen()
                }
                composable(Destination.Settings.route) {
                    SettingsScreen(
                        onSignedOut = {
                            // Back to the first screen with nothing behind it. Leaving an
                            // archive must not leave its screens on the back stack for a
                            // gesture to walk back into.
                            navController.navigate(Destination.Onboarding.route) {
                                popUpTo(navController.graph.id) { inclusive = true }
                            }
                        }
                    )
                }
                composable(Destination.AddDocument.route) {
                    AddDocumentScreen(
                        onSaved = { storyId ->
                            navController.navigate(Destination.StoryDetail.of(storyId)) {
                                popUpTo(Destination.AddDocument.route) { inclusive = true }
                            }
                        }
                    )
                }
                composable(Destination.Librarian.route) {
                    LibrarianScreen(
                        onOpenStory = { navController.navigate(Destination.StoryDetail.of(it)) }
                    )
                }
                composable(Destination.Search.route) {
                    SearchScreen(
                        onOpenStory = { navController.navigate(Destination.StoryDetail.of(it)) }
                    )
                }
                composable(Destination.Record.route) {
                    RecordScreen(
                        onDone = { navController.navigate(Destination.ReviewSave.route) }
                    )
                }
                composable(Destination.ReviewSave.route) {
                    val recording by RecordingBus.state.collectAsStateWithLifecycle()
                    val path = recording.outputPath
                    if (path == null) {
                        // Nothing to review. Can happen if the process was killed between
                        // stopping and saving.
                        LaunchedEffect(Unit) { navController.popBackStack() }
                    } else {
                        ReviewSaveScreen(
                            localAudioPath = path,
                            durationMs = recording.elapsedMs,
                            nowMillis = System.currentTimeMillis(),
                            onSaved = { storyId ->
                                RecordingBus.reset()
                                navController.navigate(Destination.StoryDetail.of(storyId)) {
                                    popUpTo(Destination.Family.route)
                                }
                            }
                        )
                    }
                }
                composable(
                    route = Destination.StoryDetail.route,
                    arguments = listOf(navArgument("storyId") { type = NavType.StringType })
                ) {
                    StoryDetailScreen()
                }
            }
        }
    }
    }
}
