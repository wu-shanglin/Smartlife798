@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.smartlife798.app

import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Bundle
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.lifecycle.withResumed
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.smartlife798.app.core.Preview
import com.smartlife798.app.ui.CardDestination
import com.smartlife798.app.ui.CardOrigin
import com.smartlife798.app.ui.CardSource
import com.smartlife798.app.ui.SharedCardScene
import com.smartlife798.app.ui.currentCardSource
import com.smartlife798.app.ui.sharedCard
import com.smartlife798.app.ui.SharedCardPage
import com.smartlife798.app.ui.DeviceDetailScreen
import com.smartlife798.app.ui.DevicesScreen
import com.smartlife798.app.ui.HomeScreen
import com.smartlife798.app.ui.LoginScreen
import com.smartlife798.app.ui.ManualScanScreen
import com.smartlife798.app.ui.MeScreen
import com.smartlife798.app.ui.DeveloperToolsScreen
import com.smartlife798.app.ui.NfcCaptureScreen
import com.smartlife798.app.ui.OrdersScreen
import com.smartlife798.app.ui.ScanScreen
import com.smartlife798.app.ui.WalletScreen
import com.smartlife798.app.ui.AppUpdatePrompt
import com.smartlife798.app.ui.theme.ExpressiveSpatial
import com.smartlife798.app.ui.theme.SmartLifeTheme
import com.smartlife798.app.vm.MainViewModel

class MainActivity : ComponentActivity() {
    private var nfcIntent by androidx.compose.runtime.mutableStateOf<Intent?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptNfcIntent(intent)
    }

    private fun acceptNfcIntent(intent: Intent?) {
        if (intent == null || (BuildConfig.DEBUG && intent.getBooleanExtra("preview", false))) return
        nfcIntent = if (intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED || intent.action == Intent.ACTION_VIEW)
            Intent(intent) else null
    }

    override fun onStop() {
        nfcIntent = null
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        acceptNfcIntent(intent)
        setContent {
            val themeMode by com.smartlife798.app.core.Appearance.mode.collectAsStateWithLifecycle()
            val dynamicColor by com.smartlife798.app.core.Appearance.dynamicColor.collectAsStateWithLifecycle()
            val dark = when (themeMode) {
                com.smartlife798.app.core.ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
                com.smartlife798.app.core.ThemeMode.LIGHT -> false
                com.smartlife798.app.core.ThemeMode.DARK -> true
            }
            SmartLifeTheme(darkTheme = dark, dynamicColor = dynamicColor) {
                AppRoot(
                    forcePreview = BuildConfig.DEBUG &&
                        intent?.getBooleanExtra("preview", false) == true,
                    onExitApp = ::finishAndRemoveTask,
                    incomingNfc = nfcIntent,
                    onNfcConsumed = {
                        nfcIntent = null
                        // Avoid replaying an already consumed card after activity recreation.
                        if (intent?.action in setOf(NfcAdapter.ACTION_NDEF_DISCOVERED, Intent.ACTION_VIEW))
                            setIntent(Intent(intent).apply { data = null; removeExtra(NfcAdapter.EXTRA_NDEF_MESSAGES); removeExtra(NfcAdapter.EXTRA_TAG); removeExtra(NfcAdapter.EXTRA_ID) })
                    },
                )
            }
        }
    }
}

private data class Tab(val route: String, val label: String, val icon: @Composable () -> Unit)

@Composable
private fun AppRoot(
    forcePreview: Boolean = false,
    onExitApp: () -> Unit,
    incomingNfc: Intent? = null,
    onNfcConsumed: () -> Unit = {},
) {
    val vm: MainViewModel = viewModel()
    val loggedIn by vm.loggedIn.collectAsStateWithLifecycle()
    val unpaidCount by vm.unpaidCount.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsStateWithLifecycle()
    val updatePolicy by vm.updatePolicy.collectAsStateWithLifecycle()
    val showUpdate by vm.showUpdate.collectAsStateWithLifecycle()
    val checkingUpdate by vm.checkingUpdate.collectAsStateWithLifecycle()
    val updateDownload by vm.updateDownload.collectAsStateWithLifecycle()
    val updateRequired = !forcePreview && !Preview.enabled &&
        updatePolicy?.required(BuildConfig.VERSION_CODE) == true

    LaunchedEffect(forcePreview) {
        if (forcePreview) vm.startPreview()
    }

    // Every resume refreshes the visible page — no manual refresh buttons anywhere.
    LifecycleResumeEffect(Unit) {
        vm.setAppResumed(true, allowAutoCheckIn = !forcePreview)
        if (!forcePreview) vm.checkAppUpdate()
        if (loggedIn) vm.bumpRefresh()
        onPauseOrDispose { vm.setAppResumed(false) }
    }


    LaunchedEffect(message) {
        val text = message
        if (text != null) {
            snackbar.showSnackbar(text)
            vm.clearMessage()
        }
    }

    if (!forcePreview && !Preview.enabled) {
        AppUpdatePrompt(
            vm = vm,
            policy = updatePolicy,
            visible = showUpdate || updateRequired,
            checking = checkingUpdate,
            download = updateDownload,
            onDismiss = vm::dismissAppUpdate,
            onExit = onExitApp,
            onRetry = { vm.checkAppUpdate(manual = true) },
            onError = vm::notify,
        )
    }
    if (updateRequired) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Text("请更新后继续使用", modifier = Modifier.align(Alignment.Center))
            SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
        }
        return
    }

    if (!loggedIn) {
        Box(Modifier.fillMaxSize()) {
            LoginScreen(vm = vm)
            androidx.compose.material3.TextButton(
                onClick = { vm.checkAppUpdate(manual = true) },
                enabled = !checkingUpdate,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
            ) { Text(if (checkingUpdate) "正在检查更新…" else "检查客户端更新") }
            SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
        }
        return
    }

    // Freeze the graph for this signed-in UI session. Changing the preference
    // must not navigate away from settings or reset a restored back stack.
    val startDestination = rememberSaveable { vm.startupPage.value.route }
    val navController = rememberNavController()
    val currentEntry by navController.currentBackStackEntryAsState()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(incomingNfc, currentEntry?.id, loggedIn, updateRequired, lifecycleOwner) {
        val raw = incomingNfc ?: return@LaunchedEffect
        val entry = currentEntry ?: return@LaunchedEffect
        if (updateRequired || forcePreview || Preview.enabled) return@LaunchedEffect
        lifecycleOwner.lifecycle.withResumed { }
        if (entry.destination.route == "developer-tools/nfc") {
            vm.notify("请在采集页点击开始采集后再碰卡")
        } else {
            val input = com.smartlife798.app.core.NfcIntentInput.read(raw)
            if (input != null && lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                vm.acceptNfcUris(input.uris, autoStart = input.allowsAutoStart())
            }
        }
        onNfcConsumed()
    }

    val nfcDevice by vm.nfcDevice.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? android.app.Activity
    val nfcReader = remember(activity, vm) {
        activity?.let { com.smartlife798.app.core.NfcDeviceReader(it) { uris -> vm.acceptNfcUris(uris, autoStart = true) } }
    }
    val normalNfc = currentEntry != null && currentEntry?.destination?.route != "developer-tools/nfc" &&
        !forcePreview && !Preview.enabled
    LifecycleResumeEffect(nfcReader, normalNfc) {
        if (normalNfc) nfcReader?.start()
        onPauseOrDispose { nfcReader?.stop() }
    }
    LaunchedEffect(nfcDevice, currentEntry?.id) {
        val request = nfcDevice ?: return@LaunchedEffect
        val entry = currentEntry ?: return@LaunchedEffect
        entry.lifecycle.withResumed {
            if (request.autoStartTicket != null || entry.arguments?.getString("id") != request.deviceId) {
                val ticket = request.autoStartTicket.orEmpty()
                navController.navigate("device/${Uri.encode(request.deviceId)}?autoStart=${ticket.isNotEmpty()}&nfcTicket=${Uri.encode(ticket)}") {
                    launchSingleTop = entry.arguments?.getString("id") == request.deviceId
                }
            }
            vm.consumeNfcDevice(request)
        }
    }

    val hasBottomBar = currentEntry?.destination?.route in setOf("home", "devices", "orders", "me")
    var bottomBarHeight by remember { mutableIntStateOf(0) }
    val systemBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val snackbarBottom = if (hasBottomBar)
        maxOf(with(LocalDensity.current) { bottomBarHeight.toDp() }, systemBottom)
    else systemBottom
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar, modifier = Modifier.padding(bottom = snackbarBottom)) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { inner ->
        Column(
            Modifier.padding(inner).statusBarsPadding(),
        ) {
            if (Preview.enabled) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "演示模式 · 以下为静态示例数据，未连接服务端",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
            // Container transforms share the actual clicked card with its full-screen page.
            // The overlay is clipped only at the app content boundary, not by lazy-list items.
            SharedTransitionLayout(Modifier.fillMaxSize().clipToBounds()) {
                val sharedScope = this
                // Wait for navigation AND shared bounds, which can outlive the
                // destination fade, before refreshing any outgoing source lists.
                LaunchedEffect(navController, loggedIn) {
                    if (loggedIn) {
                        navController.currentBackStackEntryFlow.collectLatest { entry ->
                            entry.lifecycle.withResumed { }
                            snapshotFlow { !sharedScope.isTransitionActive }.first { it }
                            entry.lifecycle.withResumed { vm.bumpRefresh() }
                        }
                    }
                }
                NavHost(
                    navController = navController,
                    startDestination = startDestination,
                    modifier = Modifier.fillMaxSize(),
                    enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None },
                ) {
                    composable("home") { entry ->
                        SharedCardScene(sharedScope, this, entry.id) {
                            MainTabPage("home", navController, unpaidCount, onBottomBarHeight = { bottomBarHeight = it }) {
                                HomeScreen(
                                    vm = vm,
                                    onScan = {
                                        openCardPage(navController, "scan", CardSource(entry.id, CardOrigin.HOME_SCAN))
                                    },
                                    onOpenDevice = { id, origin ->
                                        openDeviceCard(navController, id, CardSource(entry.id, origin))
                                    },
                                    onOpenDevices = { shelf ->
                                        vm.requestShelf(shelf)
                                        navigateTab(navController, "devices")
                                    },
                                    onOpenOrders = { navigateTab(navController, "orders") },
                                )
                            }
                        }
                    }
                    composable("devices") { entry ->
                        SharedCardScene(sharedScope, this, entry.id) {
                            MainTabPage("devices", navController, unpaidCount, onBottomBarHeight = { bottomBarHeight = it }) {
                                DevicesScreen(
                                    vm = vm,
                                    onOpenDevice = { id, origin ->
                                        openDeviceCard(navController, id, CardSource(entry.id, origin))
                                    },
                                )
                            }
                        }
                    }
                    composable("orders") { entry ->
                        SharedCardScene(sharedScope, this, entry.id) {
                            MainTabPage("orders", navController, unpaidCount, onBottomBarHeight = { bottomBarHeight = it }) {
                                OrdersScreen(
                                    vm = vm,
                                    onOpenDevice = { id, origin ->
                                        openDeviceCard(navController, id, CardSource(entry.id, origin))
                                    },
                                )
                            }
                        }
                    }
                    composable("me") { entry ->
                        SharedCardScene(sharedScope, this, entry.id) {
                            MainTabPage("me", navController, unpaidCount, onBottomBarHeight = { bottomBarHeight = it }) {
                                MeScreen(
                                    vm = vm,
                                    onOpenDeveloperTools = { navController.navigate("developer-tools") { launchSingleTop = true } },
                                    onOpenWallet = {
                                        openCardPage(navController, "wallet", CardSource(entry.id, CardOrigin.WALLET))
                                    },
                                )
                            }
                        }
                    }
                    composable("developer-tools") {
                        val enabled by vm.developerToolsEnabled.collectAsStateWithLifecycle()
                        DeveloperToolsScreen(
                            enabled = enabled,
                            onOpenNfc = { navController.navigate("developer-tools/nfc") { launchSingleTop = true } },
                            onDisable = { vm.setDeveloperToolsEnabled(false) },
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("developer-tools/nfc") {
                        val enabled by vm.developerToolsEnabled.collectAsStateWithLifecycle()
                        NfcCaptureScreen(
                            enabled = enabled,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(
                        "wallet?cardOrigin={cardOrigin}&cardEntry={cardEntry}",
                        arguments = cardArguments(),
                        enterTransition = {
                            if (targetState.cardSource(CardDestination.WALLET) != null) fadeIn()
                            else fadeIn() + scaleIn(initialScale = 1.1f)
                        },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = {
                            if (initialState.cardSource(CardDestination.WALLET) != null) fadeOut()
                            else scaleOut(targetScale = 0.9f)
                        },
                    ) { entry ->
                        SharedCardScene(sharedScope, this, entry.id) {
                            val source = entry.cardSource(CardDestination.WALLET)
                            SharedCardPage(source) {
                                WalletScreen(vm = vm, onBack = { navController.popBackStack() })
                            }
                        }
                    }
                    composable(
                        "scan?cardOrigin={cardOrigin}&cardEntry={cardEntry}",
                        arguments = cardArguments(),
                        enterTransition = {
                            if (targetState.cardSource(CardDestination.SCAN) != null) fadeIn()
                            else fadeIn() + scaleIn(initialScale = 1.1f)
                        },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = {
                            if (initialState.cardSource(CardDestination.SCAN) != null) fadeOut()
                            else scaleOut(targetScale = 0.9f)
                        },
                    ) { entry ->
                        SharedCardScene(sharedScope, this, entry.id) {
                            val source = entry.cardSource(CardDestination.SCAN)
                            SharedCardPage(source) {
                                ScanScreen(
                                    vm = vm,
                                    onClose = { navController.popBackStack() },
                                    onManualInput = {
                                        openCardPage(navController, "scan/manual", CardSource(entry.id, CardOrigin.SCAN_MANUAL))
                                    },
                                    onOpenDevice = { id -> openScannedDevice(navController, id) },
                                )
                            }
                        }
                    }
                    composable(
                        "scan/manual?cardOrigin={cardOrigin}&cardEntry={cardEntry}",
                        arguments = cardArguments(),
                        enterTransition = {
                            if (targetState.cardSource(CardDestination.MANUAL) != null) fadeIn()
                            else fadeIn() + scaleIn(initialScale = 1.1f)
                        },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = {
                            if (initialState.cardSource(CardDestination.MANUAL) != null) fadeOut()
                            else scaleOut(targetScale = 0.9f)
                        },
                    ) { entry ->
                        SharedCardScene(sharedScope, this, entry.id) {
                            val source = entry.cardSource(CardDestination.MANUAL)
                            SharedCardPage(source) {
                                ManualScanScreen(
                                    vm = vm,
                                    onBack = { navController.popBackStack() },
                                    onOpenDevice = { id -> openScannedDevice(navController, id) },
                                )
                            }
                        }
                    }
                    composable(
                        "device/{id}?autoStart={autoStart}&nfcTicket={nfcTicket}&cardOrigin={cardOrigin}&cardEntry={cardEntry}",
                        arguments = listOf(
                            navArgument("id") { type = NavType.StringType },
                            navArgument("nfcTicket") { type = NavType.StringType; defaultValue = "" },
                            navArgument("autoStart") {
                                type = NavType.BoolType
                                defaultValue = false
                            },
                        ) + cardArguments(),
                        enterTransition = {
                            // sharedBounds already cross-fades the container. A second
                            // full-page alpha animation duplicates blending and timing.
                            if (targetState.cardSource(CardDestination.DEVICE) != null) EnterTransition.None
                            else fadeIn() + scaleIn(initialScale = 1.1f)
                        },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = {
                            if (initialState.cardSource(CardDestination.DEVICE) != null) ExitTransition.None
                            else scaleOut(targetScale = 0.9f)
                        },
                    ) { entry ->
                        val id = entry.arguments?.getString("id").orEmpty()
                        val autoStart = entry.arguments?.getBoolean("autoStart") ?: false
                        SharedCardScene(sharedScope, this, entry.id) {
                            val source = entry.cardSource(CardDestination.DEVICE)
                            SharedCardPage(source, id) {
                                DeviceDetailScreen(
                                    vm = vm,
                                    deviceId = id,
                                    autoStart = autoStart,
                                    nfcTicket = entry.arguments?.getString("nfcTicket").orEmpty(),
                                    onBack = { navController.popBackStack() },
                                    onOpenOrders = {
                                        navController.popBackStack()
                                        navigateTab(navController, "orders")
                                    },
                                    onNeedPayment = {
                                        navigateTab(navController, "orders")
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Keep bottom navigation in the page being previewed, without resizing NavHost on pop. */
@Composable
private fun MainTabPage(
    route: String,
    nav: NavHostController,
    unpaidCount: Int,
    onBottomBarHeight: (Int) -> Unit,
    content: @Composable () -> Unit,
) {
    val tabs = listOf(
        Tab("home", "首页") { Icon(Icons.Filled.Home, contentDescription = null) },
        Tab("devices", "设备") { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
        Tab("orders", "订单") { Icon(Icons.Filled.ShoppingCart, contentDescription = null) },
        Tab("me", "我的") { Icon(Icons.Filled.Person, contentDescription = null) },
    )
    val scanSource = currentCardSource(CardOrigin.NAV_SCAN)
    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.selectableGroup().onSizeChanged { onBottomBarHeight(it.height) },
            ) {
                tabs.take(2).forEach { tab ->
                    ExpressiveNavItem(
                        selected = route == tab.route,
                        label = tab.label,
                        icon = tab.icon,
                        badgeCount = 0,
                        onClick = { navigateTab(nav, tab.route) },
                    )
                }
                ScanNavButton(onClick = { openCardPage(nav, "scan", scanSource) })
                tabs.drop(2).forEach { tab ->
                    ExpressiveNavItem(
                        selected = route == tab.route,
                        label = tab.label,
                        icon = tab.icon,
                        badgeCount = if (tab.route == "orders") unpaidCount else 0,
                        onClick = { navigateTab(nav, tab.route) },
                    )
                }
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(bottom = inner.calculateBottomPadding())) {
            content()
        }
    }
}

/**
 * Expressive navigation item: animated pill container behind the icon, spring
 * scale on the icon and colour cross-fade. Built from stable material3 pieces.
 */
@Composable
private fun RowScope.ExpressiveNavItem(
    selected: Boolean,
    label: String,
    icon: @Composable () -> Unit,
    badgeCount: Int,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // The press feedback and the settled selection use the same pill. This is
    // what makes the tap preview and the post-navigation highlight consistent.
    val container = animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.primaryContainer
            pressed -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f)
            else -> Color.Transparent
        },
        animationSpec = tween(180),
        label = "navContainer",
    )
    val content by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(180),
        label = "navContent",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.08f else if (pressed) 0.94f else 1f,
        animationSpec = ExpressiveSpatial,
        label = "navIconScale",
    )
    Box(
        modifier = Modifier
            .weight(1f)
            .height(60.dp)
            .padding(horizontal = 3.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(18.dp))
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Tab,
                onClick = onClick,
            )
            .drawBehind { drawRect(container.value) },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            val iconContent: @Composable () -> Unit = {
                Box(
                    modifier = Modifier.graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                    },
                ) {
                    CompositionLocalProvider(LocalContentColor provides content) { icon() }
                }
            }
            if (badgeCount > 0) {
                BadgedBox(
                    badge = {
                        Badge(containerColor = MaterialTheme.colorScheme.error) {
                            Text(if (badgeCount > 99) "99+" else "$badgeCount")
                        }
                    },
                ) { iconContent() }
            } else {
                iconContent()
            }
            Spacer(Modifier.height(3.dp))
            Text(
                label,
                color = content,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun RowScope.ScanNavButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = ExpressiveSpatial,
        label = "scanScale",
    )
    Box(
        modifier = Modifier
            .weight(1f)
            .height(60.dp),
        contentAlignment = Alignment.Center,
    ) {
        FilledIconButton(
            onClick = onClick,
            shapes = IconButtonDefaults.shapes(),
            interactionSource = interaction,
            modifier = Modifier
                .sharedCard(CardOrigin.NAV_SCAN)
                .size(54.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .semantics { contentDescription = "扫码" },
        ) {
            QrGlyph(
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

private fun cardArguments() = listOf(
    navArgument("cardOrigin") {
        type = NavType.StringType
        nullable = true
        defaultValue = null
    },
    navArgument("cardEntry") {
        type = NavType.StringType
        nullable = true
        defaultValue = null
    },
)

private fun NavBackStackEntry.cardSource(destination: CardDestination): CardSource? =
    CardSource.decode(arguments?.getString("cardEntry"), arguments?.getString("cardOrigin"), destination)

private fun cardQuery(source: CardSource?): String = source?.let {
    "cardOrigin=${it.origin.name}&cardEntry=${Uri.encode(it.entryId)}"
}.orEmpty()

private fun openDeviceCard(nav: NavHostController, id: String, source: CardSource) {
    if (!source.isCurrent(nav.currentBackStackEntry?.id)) return
    nav.navigate("device/${Uri.encode(id)}?${cardQuery(source)}") { launchSingleTop = true }
}

private fun openCardPage(nav: NavHostController, route: String, source: CardSource?) {
    if (source != null && !source.isCurrent(nav.currentBackStackEntry?.id)) return
    nav.navigate("$route?${cardQuery(source)}") { launchSingleTop = true }
}

private fun openScannedDevice(nav: NavHostController, id: String) {
    nav.navigate("device/$id?autoStart=true") {
        // Manual input lives above scan; popping to scan inclusively destroys
        // both so back from the detail page never lands on a dead scanner.
        popUpTo("scan?cardOrigin={cardOrigin}&cardEntry={cardEntry}") { inclusive = true }
    }
}

private fun navigateTab(nav: NavHostController, route: String) {
    nav.navigate(route) {
        popUpTo(nav.graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Small QR glyph drawn with Canvas so no extra icon dependency is required. */
@Composable
fun QrGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val s = size.minDimension
        val unit = s / 9f
        val stroke = Stroke(width = unit * 0.9f)
        fun finder(x: Float, y: Float) {
            drawRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(x * unit, y * unit),
                size = androidx.compose.ui.geometry.Size(unit * 2.6f, unit * 2.6f),
                style = stroke,
            )
        }
        finder(0.2f, 0.2f)
        finder(6.2f, 0.2f)
        finder(0.2f, 6.2f)
        drawCircle(color, radius = unit * 0.45f, center = androidx.compose.ui.geometry.Offset(unit * 6.8f, unit * 6.8f))
        drawRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(unit * 4.2f, unit * 1.2f),
            size = androidx.compose.ui.geometry.Size(unit * 0.9f, unit * 2.4f),
        )
        drawRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(unit * 1.2f, unit * 4.2f),
            size = androidx.compose.ui.geometry.Size(unit * 2.4f, unit * 0.9f),
        )
        drawRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(unit * 4.4f, unit * 4.4f),
            size = androidx.compose.ui.geometry.Size(unit * 1.0f, unit * 1.0f),
        )
    }
}
