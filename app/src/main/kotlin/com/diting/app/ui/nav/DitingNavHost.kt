@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.diting.app.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.diting.app.device.DeviceService
import com.diting.app.ui.components.DitingBottomBar
import com.diting.app.ui.screens.BrainScreen
import com.diting.app.ui.screens.ChatScreen
import com.diting.app.ui.screens.DestinationsScreen
import com.diting.app.ui.screens.DevicesScreen
import com.diting.app.ui.screens.GrowthScreen
import com.diting.app.ui.screens.HotwordsScreen
import com.diting.app.ui.screens.InsightsScreen
import com.diting.app.ui.screens.MeScreen
import com.diting.app.ui.screens.MemoryScreen
import com.diting.app.ui.screens.ModelsScreen
import com.diting.app.ui.screens.OnboardingScreen
import com.diting.app.ui.screens.PairingScreen
import com.diting.app.ui.screens.RecordingScreen
import com.diting.app.ui.screens.ReportScreen
import com.diting.app.ui.screens.ReviewScreen
import com.diting.app.ui.screens.ScenesScreen
import com.diting.app.ui.screens.SearchScreen
import com.diting.app.ui.screens.SessionDetailScreen
import com.diting.app.ui.screens.SessionsScreen
import com.diting.app.ui.screens.SubscriptionScreen
import com.diting.app.ui.screens.TaskDetailScreen
import com.diting.app.ui.screens.TasksScreen
import com.diting.app.ui.screens.TeachScreen
import com.diting.app.ui.screens.TodayScreen
import com.diting.domain.model.Citation

/**
 * Wires all 22 screens.
 *
 * The bottom bar only shows on the four tab roots. Everything else is a pushed
 * screen with a back stack, which is what keeps a deep flow — 会话 → 洞察 → 报告 —
 * from stranding the user on a page with no way back.
 */
@Composable
fun DitingNavHost(
    isRecording: Boolean,
    gateCount: Int,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val context = LocalContext.current

    val isTabRoot = currentRoute in TAB_ROOTS

    /** "↩ 回到原声" — every generated surface routes through this one function. */
    val seek: (Citation) -> Unit = { citation ->
        navController.navigate(Routes.sessionDetail(citation.sessionId, citation.startMs))
    }

    val navigateToTab: (String) -> Unit = { route ->
        navController.navigate(route) {
            // Tabs are singletons: switching should not pile up copies of 今日.
            popUpTo(Routes.TODAY) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        bottomBar = {
            if (isTabRoot) {
                DitingBottomBar(
                    currentRoute = currentRoute,
                    gateCount = gateCount,
                    isRecording = isRecording,
                    onNavigate = navigateToTab,
                    onRecordTap = { if (isRecording) onStopCapture() else onStartCapture() },
                    onRecordLongPress = { navController.navigate(Routes.RECORDING) },
                )
            }
        }
    ) { padding ->
        // Keyboard: every screen with an input at the bottom must rise above it.
        Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
            NavHost(navController = navController, startDestination = Routes.TODAY) {

                // -- tabs ---------------------------------------------------

                composable(Routes.TODAY) {
                    TodayScreen(
                        onOpenSession = { navController.navigate(Routes.sessionDetail(it)) },
                        onOpenInsights = { navController.navigate(Routes.INSIGHTS) },
                        onOpenReview = {
                            navController.navigate(Routes.review(ReviewPeriod.WEEK))
                        },
                        onOpenTask = { navController.navigate(Routes.taskDetail(it)) },
                        onOpenTasks = { navController.navigate(Routes.TASKS) },
                        onOpenModels = { navController.navigate(Routes.MODELS) },
                        onSeek = seek,
                    )
                }

                composable(Routes.SESSIONS) {
                    SessionsScreen(
                        onOpenSession = { navController.navigate(Routes.sessionDetail(it)) },
                        onOpenSearch = { navController.navigate(Routes.SEARCH) },
                        onOpenReview = { navController.navigate(Routes.review(it)) },
                    )
                }

                composable(Routes.TASKS) {
                    TasksScreen(
                        onOpenTask = { navController.navigate(Routes.taskDetail(it)) },
                        onOpenChat = { navController.navigate(Routes.CHAT) },
                        onOpenGrowth = { navController.navigate(Routes.GROWTH) },
                        onOpenSession = { navController.navigate(Routes.sessionDetail(it)) },
                    )
                }

                composable(Routes.ME) {
                    MeScreen(
                        onOpenDevices = { navController.navigate(Routes.DEVICES) },
                        onOpenBrain = { navController.navigate(Routes.BRAIN) },
                        onOpenTeach = { navController.navigate(Routes.TEACH) },
                        onOpenSubscription = { navController.navigate(Routes.SUBSCRIPTION) },
                        onOpenGrowth = { navController.navigate(Routes.GROWTH) },
                        onOpenPairing = { navController.navigate(Routes.PAIRING) },
                    )
                }

                // -- record key ---------------------------------------------

                composable(Routes.RECORDING) {
                    RecordingScreen(
                        onStartPhoneCapture = onStartCapture,
                        onStopPhoneCapture = onStopCapture,
                        onOpenScenes = { navController.navigate(Routes.SCENES) },
                        onDone = { navController.popBackStack() },
                    )
                }

                // -- 记录 branch --------------------------------------------

                composable(
                    route = Routes.SESSION_DETAIL,
                    arguments = listOf(
                        navArgument("sessionId") { type = NavType.StringType },
                        navArgument("seek") {
                            type = NavType.LongType
                            defaultValue = Routes.NO_SEEK
                        },
                    ),
                ) {
                    val sessionId = it.arguments?.getString("sessionId").orEmpty()
                    SessionDetailScreen(
                        onBack = { navController.popBackStack() },
                        onSeek = seek,
                        onOpenTasks = { navController.navigate(Routes.TASKS) },
                        onOpenHotwords = { navController.navigate(Routes.HOTWORDS) },
                        onOpenReport = { navController.navigate(Routes.report(sessionId)) },
                        onOpenInsights = { navController.navigate(Routes.INSIGHTS) },
                        onOpenChat = { navController.navigate(Routes.CHAT) },
                    )
                }

                composable(Routes.SEARCH) { SearchScreen(onSeek = seek) }

                composable(Routes.INSIGHTS) {
                    InsightsScreen(
                        onBack = { navController.popBackStack() },
                        onSeek = seek,
                        onOpenSession = { navController.navigate(Routes.sessionDetail(it)) },
                        onOpenChat = { navController.navigate(Routes.CHAT) },
                        onOpenTask = { navController.navigate(Routes.taskDetail(it)) },
                        // A report is generated per session, so the insight list
                        // sends the user to pick one rather than guessing.
                        onOpenReport = { navController.navigate(Routes.SESSIONS) },
                    )
                }

                composable(
                    route = Routes.REPORT,
                    arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
                ) {
                    ReportScreen(onBack = { navController.popBackStack() }, onSeek = seek)
                }

                composable(
                    route = Routes.REVIEW,
                    arguments = listOf(navArgument("period") { type = NavType.StringType }),
                ) {
                    ReviewScreen(
                        onBack = { navController.popBackStack() },
                        onSeek = seek,
                        onOpenMemory = { navController.navigate(Routes.MEMORY) },
                        onSwitchPeriod = { period ->
                            navController.navigate(Routes.review(period)) {
                                popUpTo(Routes.REVIEW) { inclusive = true }
                            }
                        },
                    )
                }

                // -- 任务 branch --------------------------------------------

                composable(
                    route = Routes.TASK_DETAIL,
                    arguments = listOf(navArgument("taskId") { type = NavType.StringType }),
                ) {
                    TaskDetailScreen(
                        onBack = { navController.popBackStack() },
                        onSeek = seek,
                        onOpenDestinations = { navController.navigate(Routes.DESTINATIONS) },
                        onOpenModels = { navController.navigate(Routes.MODELS) },
                    )
                }

                composable(Routes.CHAT) {
                    ChatScreen(
                        onBack = { navController.popBackStack() },
                        onOpenTask = { navController.navigate(Routes.taskDetail(it)) },
                        onOpenTasks = { navController.navigate(Routes.TASKS) },
                    )
                }

                // -- 我的 branch --------------------------------------------

                composable(Routes.DEVICES) {
                    DevicesScreen(
                        onAddDevice = { navController.navigate(Routes.PAIRING) },
                        // Runs in the foreground service so a multi-minute BLE
                        // transfer survives the user leaving this screen.
                        onSync = { DeviceService.sync(context) },
                        onSyncAll = { DeviceService.sync(context, includeLarge = true) },
                        onOpenBrain = { navController.navigate(Routes.BRAIN) },
                    )
                }

                composable(Routes.PAIRING) {
                    PairingScreen(
                        // A first pairing lands straight in onboarding: the
                        // voiceprint step is what makes "说话人 A = 你" true, and
                        // asking for it later means re-listening to old recordings.
                        onPaired = {
                            navController.navigate(Routes.ONBOARDING) {
                                popUpTo(Routes.DEVICES)
                            }
                        }
                    )
                }

                composable(Routes.BRAIN) { BrainScreen() }

                composable(Routes.SUBSCRIPTION) {
                    SubscriptionScreen(onBack = { navController.popBackStack() }, onOpenModels = { navController.navigate(Routes.MODELS) }, onOpenBrain = { navController.navigate(Routes.BRAIN) })
                }

                composable(Routes.GROWTH) { GrowthScreen(onBack = { navController.popBackStack() }) }

                // -- 教小谛 --------------------------------------------------

                composable(Routes.TEACH) {
                    TeachScreen(
                        onBack = { navController.popBackStack() },
                        onOpenOnboarding = { navController.navigate(Routes.ONBOARDING) },
                        onOpenHotwords = { navController.navigate(Routes.HOTWORDS) },
                        onOpenScenes = { navController.navigate(Routes.SCENES) },
                        onOpenModels = { navController.navigate(Routes.MODELS) },
                        onOpenDestinations = { navController.navigate(Routes.DESTINATIONS) },
                        onOpenMemory = { navController.navigate(Routes.MEMORY) },
                    )
                }

                composable(Routes.ONBOARDING) {
                    OnboardingScreen(onDone = { navController.popBackStack() })
                }

                composable(Routes.HOTWORDS) { HotwordsScreen(onBack = { navController.popBackStack() }) }
                composable(Routes.SCENES) { ScenesScreen(onBack = { navController.popBackStack() }) }
                composable(Routes.MODELS) { ModelsScreen(onBack = { navController.popBackStack() }) }
                composable(Routes.DESTINATIONS) { DestinationsScreen(onBack = { navController.popBackStack() }) }
                composable(Routes.MEMORY) { MemoryScreen(onBack = { navController.popBackStack() }) }
            }
        }
    }
}

private val TAB_ROOTS = setOf(Routes.TODAY, Routes.SESSIONS, Routes.TASKS, Routes.ME)
