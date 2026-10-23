package com.example

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.db.FirewallRule
import com.example.db.VpnProfile
import com.example.db.FirewallLog
import com.example.ui.MainViewModel
import com.example.ui.MainViewModelFactory
import com.example.ui.theme.MyApplicationTheme
import com.example.vpn.VpnController
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ==========================================
// Theme Colors override for Sleek Interface Style
// ==========================================
private val DarkSlate = Color(0xFFF7F9FC)       // SleekBg
private val CardSlate = Color(0xFFFFFFFF)       // SleekCard
private val NeonCyan = Color(0xFF006495)        // SleekPrimary (Brand Blue)
private val NeonGreen = Color(0xFF0F7A50)       // SleekGreen (Emerald status)
private val CrimsonRed = Color(0xFFBA1A1A)      // SleekRed (Blocked/Warning status)
private val MutedText = Color(0xFF5C5E61)       // SleekMuted (Gray details)
private val BorderSlate = Color(0xFFE1E2E4)     // SleekBorder (Card boundaries)
private val MainText = Color(0xFF1A1C1E)        // SleekText (Primary high-contrast dark text)

class MainActivity : ComponentActivity() {

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            VpnController.startVpn(this)
            Toast.makeText(this, "VPN Authorization Successful. Starting Firewall.", Toast.LENGTH_SHORT).show()
        } else {
            // Fallback to fully functional simulation mode if the user declines or can't authorize
            VpnController.setSimulatedMode(true)
            VpnController.setVpnActive(true)
            Toast.makeText(this, "Firewall Enabled (Simulation Mode)", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkSlate
                ) {
                    val viewModel: MainViewModel = viewModel(
                        factory = MainViewModelFactory(applicationContext)
                    )
                    FirewallAppMainScreen(
                        viewModel = viewModel,
                        onRequestVpnPermission = { tryToggleVpn() }
                    )
                }
            }
        }
    }

    private fun tryToggleVpn() {
        if (VpnController.isVpnActive.value) {
            // Disable firewall immediately without requesting permissions
            if (VpnController.isSimulatedMode.value) {
                VpnController.setSimulatedMode(false)
                VpnController.setVpnActive(false)
            } else {
                VpnController.stopVpn(this)
            }
            Toast.makeText(this, "Firewall Disabled.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            VpnController.setSimulatedMode(false)
            val intent = VpnService.prepare(this)
            if (intent != null) {
                vpnPermissionLauncher.launch(intent)
            } else {
                // Permission already granted, directly enable
                try {
                    VpnController.startVpn(this)
                    Toast.makeText(this, "Firewall Enabled & Securing network...", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Failed to start VPN foreground service, toggling local state: ${e.message}")
                    VpnController.setSimulatedMode(true)
                    VpnController.setVpnActive(true)
                    Toast.makeText(this, "Firewall Enabled (Simulation Mode)", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: SecurityException) {
            android.util.Log.w("MainActivity", "VPN Service prepare SecurityException: ${e.message}. Falling back to clean simulation.")
            VpnController.setSimulatedMode(true)
            VpnController.setVpnActive(true)
            Toast.makeText(this, "Firewall Enabled (Simulation Mode)", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "VPN preparation failed: ${e.message}")
            // Fallback to fully featured simulation mode
            VpnController.setSimulatedMode(true)
            VpnController.setVpnActive(true)
            Toast.makeText(this, "Firewall Enabled (Simulation Mode)", Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
fun FirewallAppMainScreen(
    viewModel: MainViewModel,
    onRequestVpnPermission: () -> Unit
) {
    val selectedTab by viewModel.selectedTabIndex.collectAsStateWithLifecycle()
    val isVpnActive by VpnController.isVpnActive.collectAsStateWithLifecycle()
    val pendingLogs by viewModel.pendingLogs.collectAsStateWithLifecycle()
    val pendingCount = pendingLogs.size
    
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("app_scaffold"),
        containerColor = DarkSlate,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .background(CardSlate)
                    .drawBehind {
                        drawLine(
                            color = BorderSlate,
                            start = Offset(0f, size.height),
                            end = Offset(size.width, size.height),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(NeonCyan),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Security,
                            contentDescription = "SafeRoute",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "SafeRoute",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MainText
                        )
                        Text(
                            text = if (isVpnActive) "FIREWALL ACTIVE" else "FIREWALL INACTIVE",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isVpnActive) NeonGreen else CrimsonRed,
                            letterSpacing = 1.sp
                        )
                    }
                }
                
                // Active Switch Toggle pill matching Sleek design
                Box(
                    modifier = Modifier
                        .size(width = 46.dp, height = 24.dp)
                        .clip(CircleShape)
                        .background(if (isVpnActive) NeonCyan else MutedText.copy(alpha = 0.2f))
                        .clickable { onRequestVpnPermission() }
                        .padding(2.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .align(if (isVpnActive) Alignment.CenterEnd else Alignment.CenterStart)
                    )
                }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = CardSlate,
                tonalElevation = 8.dp,
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .border(1.dp, BorderSlate, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { viewModel.updateSelectedTab(0) },
                    icon = { Icon(Icons.Rounded.Security, contentDescription = "Dashboard") },
                    label = { Text("Dashboard", style = MaterialTheme.typography.labelSmall) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = NeonCyan,
                        selectedTextColor = NeonCyan,
                        unselectedIconColor = MutedText,
                        unselectedTextColor = MutedText,
                        indicatorColor = Color(0xFFD1E4FF)
                    ),
                    modifier = Modifier.testTag("nav_dashboard")
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { viewModel.updateSelectedTab(1) },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (pendingCount > 0) {
                                    Badge(
                                        containerColor = CrimsonRed,
                                        contentColor = Color.White
                                    ) {
                                        Text("$pendingCount", fontSize = 9.sp)
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Rounded.RuleFolder, contentDescription = "Pending Rules")
                        }
                    },
                    label = { Text("Pending", style = MaterialTheme.typography.labelSmall) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = NeonCyan,
                        selectedTextColor = NeonCyan,
                        unselectedIconColor = MutedText,
                        unselectedTextColor = MutedText,
                        indicatorColor = Color(0xFFD1E4FF)
                    ),
                    modifier = Modifier.testTag("nav_pending")
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { viewModel.updateSelectedTab(2) },
                    icon = { Icon(Icons.Rounded.AppBlocking, contentDescription = "Firewall Rules") },
                    label = { Text("Firewall", style = MaterialTheme.typography.labelSmall) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = NeonCyan,
                        selectedTextColor = NeonCyan,
                        unselectedIconColor = MutedText,
                        unselectedTextColor = MutedText,
                        indicatorColor = Color(0xFFD1E4FF)
                    ),
                    modifier = Modifier.testTag("nav_firewall")
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { viewModel.updateSelectedTab(3) },
                    icon = { Icon(Icons.Rounded.History, contentDescription = "Access Logs") },
                    label = { Text("Logs", style = MaterialTheme.typography.labelSmall) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = NeonCyan,
                        selectedTextColor = NeonCyan,
                        unselectedIconColor = MutedText,
                        unselectedTextColor = MutedText,
                        indicatorColor = Color(0xFFD1E4FF)
                    ),
                    modifier = Modifier.testTag("nav_logs")
                )
                NavigationBarItem(
                    selected = selectedTab == 4,
                    onClick = { viewModel.updateSelectedTab(4) },
                    icon = { Icon(Icons.Rounded.Router, contentDescription = "Gateway") },
                    label = { Text("Gateway", style = MaterialTheme.typography.labelSmall) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = NeonCyan,
                        selectedTextColor = NeonCyan,
                        unselectedIconColor = MutedText,
                        unselectedTextColor = MutedText,
                        indicatorColor = Color(0xFFD1E4FF)
                    ),
                    modifier = Modifier.testTag("nav_gateway")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> DashboardTabScreen(viewModel, onRequestVpnPermission)
                1 -> PendingRequestsScreen(viewModel)
                2 -> FirewallRulesScreen(viewModel)
                3 -> AccessLogsScreen(viewModel)
                4 -> GatewayMonitorScreen(viewModel)
            }
        }
    }
}

// ==========================================
// Tab Screen 1: Dashboard / HUD
// ==========================================
@Composable
fun DashboardTabScreen(
    viewModel: MainViewModel,
    onRequestVpnPermission: () -> Unit
) {
    val isVpnActive by VpnController.isVpnActive.collectAsStateWithLifecycle()
    val dlSpeed by VpnController.downloadSpeed.collectAsStateWithLifecycle()
    val ulSpeed by VpnController.uploadSpeed.collectAsStateWithLifecycle()
    val totalTraffic by VpnController.totalTrafficTransferred.collectAsStateWithLifecycle()
    val blockedCount by VpnController.blockedAttempts.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val isAlwaysOn by VpnController.isAlwaysOn.collectAsStateWithLifecycle()
    val isLockdownEnabled by VpnController.isLockdownEnabled.collectAsStateWithLifecycle()

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Title HUD
        Text(
            text = "SECURITY OPERATIONS DASHBOARD",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = NeonCyan,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(top = 12.dp, bottom = 16.dp)
        )

        // Pulsing Shield Toggle Button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(200.dp)
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                (if (isVpnActive) NeonCyan else CrimsonRed).copy(alpha = 0.15f * pulseScale),
                                Color.Transparent
                            )
                        ),
                        radius = size.minDimension / 1.5f
                    )
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onRequestVpnPermission
                )
                .testTag("shield_toggle_btn")
        ) {
            Card(
                shape = CircleShape,
                colors = CardDefaults.cardColors(containerColor = CardSlate),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                border = BorderStroke(
                    width = 2.5.dp,
                    color = if (isVpnActive) NeonCyan else CrimsonRed
                ),
                modifier = Modifier
                    .size(150.dp * if (isVpnActive) pulseScale else 1f)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = if (isVpnActive) Icons.Rounded.VerifiedUser else Icons.Rounded.GppBad,
                        contentDescription = "Shield Toggle",
                        tint = if (isVpnActive) NeonCyan else CrimsonRed,
                        modifier = Modifier.size(52.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isVpnActive) "PROTECTED" else "UNSECURED",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = if (isVpnActive) NeonGreen else CrimsonRed,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isVpnActive) "TAP TO STOP" else "TAP TO START",
                        fontSize = 10.sp,
                        color = MutedText
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Active Profile Ribbon info
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CardSlate),
            border = BorderStroke(1.dp, BorderSlate),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (activeProfile != null) Icons.Rounded.CloudQueue else Icons.Rounded.AdminPanelSettings,
                    contentDescription = "Tunnel Gateway",
                    tint = if (activeProfile != null) NeonCyan else NeonGreen,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "TUNNEL CONFIGURATION",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MutedText,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = activeProfile?.profileName ?: "Local Firewall Mode (Direct Routing)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MainText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (activeProfile != null) {
                        Text(
                            text = "Split-tunneling: ${if (activeProfile!!.splitTunneling) "Enabled" else "Full Device Tunnel"} | MTU: ${activeProfile!!.mtu}",
                            fontSize = 11.sp,
                            color = NeonCyan
                        )
                    } else {
                        Text(
                            text = "Black-holes traffic from selected blocklist apps",
                            fontSize = 11.sp,
                            color = MutedText
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Hardware details HUD Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HUDStatCard(
                icon = Icons.Rounded.FileDownload,
                title = "DOWNLOAD",
                value = formatSpeed(dlSpeed),
                accentColor = NeonCyan,
                modifier = Modifier.weight(1f)
            )
            HUDStatCard(
                icon = Icons.Rounded.FileUpload,
                title = "UPLOAD",
                value = formatSpeed(ulSpeed),
                accentColor = NeonGreen,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HUDStatCard(
                icon = Icons.Rounded.DataUsage,
                title = "SECURED TRAFFIC",
                value = formatBytes(totalTraffic),
                accentColor = MainText,
                modifier = Modifier.weight(1f)
            )
            HUDStatCard(
                icon = Icons.Rounded.SecurityUpdateWarning,
                title = "ATTEMPTS BLOCKED",
                value = "$blockedCount events",
                accentColor = CrimsonRed,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Always-On VPN & Security Configuration Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CardSlate),
            border = BorderStroke(1.dp, BorderSlate),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .testTag("always_on_settings_card")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SettingsBackupRestore,
                        contentDescription = "Always-on VPN Config",
                        tint = NeonCyan,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "SYSTEM ALWAYS-ON VPN",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MainText,
                        letterSpacing = 0.5.sp
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Ensure continuous firewall protection and auto-start upon device reboots by configuring this app as your system Always-on VPN.",
                    fontSize = 12.sp,
                    color = MutedText,
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Real-Time System Status Badges
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Always-on Status Badge
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isAlwaysOn) NeonGreen.copy(alpha = 0.1f)
                                else BorderSlate
                            )
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "ALWAYS-ON STATUS",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isAlwaysOn) NeonGreen else MutedText,
                                letterSpacing = 0.5.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isAlwaysOn) "ACTIVE" else "INACTIVE",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isAlwaysOn) NeonGreen else MainText
                            )
                        }
                    }

                    // Lockdown Status Badge
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isLockdownEnabled) CrimsonRed.copy(alpha = 0.1f)
                                else BorderSlate
                            )
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "LOCKDOWN STATUS",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isLockdownEnabled) CrimsonRed else MutedText,
                                letterSpacing = 0.5.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isLockdownEnabled) "ACTIVE" else "INACTIVE",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isLockdownEnabled) CrimsonRed else MainText
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Alert notification details for lockdown mode behavior
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFFFF4E5))
                        .border(1.dp, Color(0xFFFFD8A8), RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Warning,
                                contentDescription = "Lockdown Warning",
                                tint = Color(0xFFD27A00),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "System Lockdown (Block without VPN)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF862E00)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "A local firewall blocks apps on-device. If you enable the OS 'Block connections without VPN' toggle, Android blocks internet access of ALL other apps because they run outside the VPN loop. For best results, enable 'Always-on VPN' but keep 'Block connections without VPN' disabled.",
                            fontSize = 11.sp,
                            color = Color(0xFF862E00),
                            lineHeight = 15.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Configure System button
                val context = LocalContext.current
                Button(
                    onClick = {
                        try {
                            val intent = Intent("android.settings.VPN_SETTINGS")
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Redirecting failed. Please open System Settings -> Connections -> More connection settings -> VPN manually.", Toast.LENGTH_LONG).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("configure_system_vpn_btn")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.OpenInNew,
                        contentDescription = "System VPN Configuration",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "CONFIGURE SYSTEM ALWAYS-ON",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }
    }
}

@Composable
fun HUDStatCard(
    icon: ImageVector,
    title: String,
    value: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardSlate),
        border = BorderStroke(1.dp, BorderSlate),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (accentColor != MainText) accentColor else NeonCyan,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = title,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MutedText,
                    letterSpacing = 1.sp
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = value,
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
                color = accentColor
            )
        }
    }
}

// ==========================================
// Tab Screen 2: Firewall Application Manager
// ==========================================
@Composable
fun FirewallRulesScreen(viewModel: MainViewModel) {
    val filteredRules by viewModel.filteredRules.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val filterCategory by viewModel.filterCategory.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // App Title
        Text(
            text = "FIREWALL FILTER MANAGER",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = NeonCyan,
            letterSpacing = 1.sp
        )
        Text(
            text = "Control App-By-App Connectivity",
            style = MaterialTheme.typography.titleMedium,
            color = MainText,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Embedded search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { viewModel.updateSearchQuery(it) },
            placeholder = { Text("Search package or app name...", color = MutedText, fontSize = 14.sp) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = "Search", tint = NeonCyan) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Clear", tint = CrimsonRed)
                    }
                }
            },
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MainText,
                unfocusedTextColor = MainText,
                focusedBorderColor = NeonCyan,
                unfocusedBorderColor = BorderSlate,
                focusedContainerColor = CardSlate,
                unfocusedContainerColor = CardSlate
            ),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("app_search_field")
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Custom stylized category tabs row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val categories = listOf("All", "Installed", "System", "Blocked")
            categories.forEach { cat ->
                val isSelected = filterCategory == cat
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) NeonCyan else CardSlate)
                        .border(1.dp, if (isSelected) NeonCyan else BorderSlate, RoundedCornerShape(8.dp))
                        .clickable { viewModel.updateFilterCategory(cat) }
                        .padding(vertical = 8.dp)
                ) {
                    Text(
                        text = cat,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) Color.White else MainText
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Dynamic results list
        if (filteredRules.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Rounded.Storage,
                        contentDescription = "Empty",
                        tint = MutedText,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No packages matching filter.",
                        color = MutedText,
                        fontSize = 14.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("apps_rules_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredRules, key = { it.packageName }) { rule ->
                    FirewallRuleRow(
                        rule = rule,
                        onToggle = { viewModel.toggleAppBlockState(rule) }
                    )
                }
            }
        }
    }
}

@Composable
fun FirewallRuleRow(
    rule: FirewallRule,
    onToggle: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSlate),
        border = BorderStroke(1.dp, if (rule.isBlocked) CrimsonRed.copy(alpha = 0.5f) else BorderSlate),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("app_rule_row_${rule.packageName}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Rounded App Icon Helper from package manager
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(BorderSlate)
                    .padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                AppIcon(packageName = rule.packageName, modifier = Modifier.size(32.dp))
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rule.appName,
                    fontWeight = FontWeight.Bold,
                    color = MainText,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = rule.packageName,
                    fontSize = 11.sp,
                    color = MutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                if (rule.isSystemApp) Color(0x20FFA500) else Color(0x2000F0FF),
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (rule.isSystemApp) "SYSTEM" else "USER INSTALLED",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (rule.isSystemApp) Color(0xFFFFA500) else NeonCyan
                        )
                    }
                    if (rule.isBlocked) {
                        Box(
                            modifier = Modifier
                                .background(CrimsonRed.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "BLOCKED",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = CrimsonRed
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .background(NeonGreen.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "ALLOWED",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = NeonGreen
                            )
                        }
                    }
                }
            }

            IconButton(
                onClick = onToggle,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("toggle_block_${rule.packageName}")
            ) {
                Icon(
                    imageVector = if (rule.isBlocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                    contentDescription = "Toggle connectivity for app",
                    tint = if (rule.isBlocked) CrimsonRed else MutedText,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

// Android View wrapper to load Application Icon dynamically and crash-safe
@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val drawable = remember(packageName) {
        try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (e: Exception) {
            context.packageManager.defaultActivityIcon
        }
    }
    AndroidView(
        factory = { ctx ->
            ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
        },
        update = { imageView ->
            imageView.setImageDrawable(drawable)
        },
        modifier = modifier
    )
}

// ==========================================
// Tab Screen 3: Traffic speed real-time Monitor
// ==========================================
@Composable
fun TrafficMonitorScreen(viewModel: MainViewModel) {
    val dlSpeed by VpnController.downloadSpeed.collectAsStateWithLifecycle()
    val ulSpeed by VpnController.uploadSpeed.collectAsStateWithLifecycle()
    val appSpeeds by viewModel.appSpeeds.collectAsStateWithLifecycle()
    val firewallRules by viewModel.firewallRules.collectAsStateWithLifecycle()

    val totalCurrentSpeedKbSec = (dlSpeed + ulSpeed) / 1024f
    val speedHistory = remember { mutableStateListOf<Float>() }

    // Every time speeds update, record a datapoint for the live Canvas wave graph (keep last 30 intervals)
    LaunchedEffect(totalCurrentSpeedKbSec) {
        speedHistory.add(totalCurrentSpeedKbSec)
        if (speedHistory.size > 30) {
            speedHistory.removeAt(0)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Headers
        Text(
            text = "REAL-TIME BANDWIDTH MONITOR",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = NeonCyan,
            letterSpacing = 1.sp
        )
        Text(
            text = "Live Network Traffic Visualizer",
            style = MaterialTheme.typography.titleMedium,
            color = MainText,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Custom Live Graph Canvas Drawer
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = CardSlate),
            border = BorderStroke(1.dp, BorderSlate),
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "COMPOSITE BANDWIDTH WAVE",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = MutedText,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = String.format(Locale.getDefault(), "%.1f KB/s", totalCurrentSpeedKbSec),
                            fontWeight = FontWeight.Black,
                            fontSize = 18.sp,
                            color = NeonCyan
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(NeonCyan))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Down: ${formatSpeed(dlSpeed)}", fontSize = 10.sp, color = MainText)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(NeonGreen))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Up: ${formatSpeed(ulSpeed)}", fontSize = 10.sp, color = MainText)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Real-time Bezier custom Drawing
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    if (speedHistory.isEmpty()) return@Canvas

                    val maxVal = (speedHistory.maxOrNull() ?: 10f).coerceAtLeast(10f)
                    val pointsCount = speedHistory.size
                    val spacing = size.width / (30 - 1).coerceAtLeast(1)

                    val path = Path()
                    val fillPath = Path()

                    speedHistory.forEachIndexed { index, value ->
                        val x = index * spacing
                        val y = size.height - (value / maxVal) * size.height

                        if (index == 0) {
                            path.moveTo(x, y)
                            fillPath.moveTo(x, size.height)
                            fillPath.lineTo(x, y)
                        } else {
                            path.lineTo(x, y)
                            fillPath.lineTo(x, y)
                        }

                        if (index == pointsCount - 1) {
                            fillPath.lineTo(x, size.height)
                            fillPath.close()
                        }
                    }

                    // Stroke Path
                    drawPath(
                        path = path,
                        color = NeonCyan,
                        style = Stroke(width = 2.dp.toPx())
                    )

                    // Fill Gradient Path
                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(NeonCyan.copy(alpha = 0.25f), Color.Transparent),
                            startY = 0f,
                            endY = size.height
                        )
                    )

                    // Horizontal bottom boundary
                    drawLine(
                        color = BorderSlate,
                        start = Offset(0f, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = 1.dp.toPx()
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "ACTIVE BANDWIDTH CONSUMPTION BY APP",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MutedText,
            letterSpacing = 0.5.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Live speeds lists
        val activeAppsLogs = remember(appSpeeds, firewallRules) {
            appSpeeds.keys.mapNotNull { pkg ->
                val rule = firewallRules.find { it.packageName == pkg }
                val labelObj = rule?.appName ?: pkg.substringAfterLast('.')
                val speeds = appSpeeds[pkg] ?: Pair(0L, 0L)
                val isBlocked = rule?.isBlocked == true
                if (speeds.first > 0 || speeds.second > 0) {
                    ActiveAppSpeedLog(pkg, labelObj, speeds.first, speeds.second, isBlocked)
                } else null
            }.sortedByDescending { it.downSpeed + it.upSpeed }
        }

        if (activeAppsLogs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Rounded.NetworkCheck,
                        contentDescription = "Silent Network",
                        tint = MutedText,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Current apps are silent or idle.",
                        fontSize = 12.sp,
                        color = MutedText
                    )
                    Text(
                        text = "Generate network traffic (load browser etc.) to monitor",
                        fontSize = 10.sp,
                        color = MutedText.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(activeAppsLogs, key = { it.packageName }) { appLog ->
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = CardSlate),
                        border = BorderStroke(1.dp, if (appLog.isBlocked) CrimsonRed.copy(alpha = 0.3f) else BorderSlate)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(BorderSlate)
                                    .padding(2.dp)
                            ) {
                                AppIcon(packageName = appLog.packageName, modifier = Modifier.size(28.dp))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = appLog.appName,
                                    fontSize = 13.sp,
                                    color = MainText,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = appLog.packageName,
                                    fontSize = 10.sp,
                                    color = MutedText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.FileDownload,
                                        contentDescription = null,
                                        tint = NeonCyan,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = formatSpeed(appLog.downSpeed),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = NeonCyan
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.FileUpload,
                                        contentDescription = null,
                                        tint = NeonGreen,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = formatSpeed(appLog.upSpeed),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = NeonGreen
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

data class ActiveAppSpeedLog(
    val packageName: String,
    val appName: String,
    val downSpeed: Long,
    val upSpeed: Long,
    val isBlocked: Boolean
)

// ==========================================
// Tab Screen 4: Remote Gateway Configurations
// ==========================================
@Composable
fun SecureProfilesScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val profiles by viewModel.vpnProfiles.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
    var displayAddDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "SECURE REMOTE GATEWAYS",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = NeonCyan,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "VPN Encrypted Core Profile Workspace",
                    style = MaterialTheme.typography.titleMedium,
                    color = MainText
                )
            }
            Button(
                onClick = { displayAddDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .height(36.dp)
                    .testTag("add_profile_fab_btn")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "Add", tint = Color.White, modifier = Modifier.size(16.dp))
                    Text("ADD", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Info prompt to guide the user
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = CardSlate),
            border = BorderStroke(1.dp, BorderSlate.copy(alpha = 0.8f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Secure Tunneling Concept:",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = NeonCyan
                )
                Text(
                    text = "Selecting and activating a secure gateway routing profile encapsulates and tunnels all outbound packets through the virtual interface, securing host connections and hiding device locations.",
                    fontSize = 10.sp,
                    color = MutedText,
                    lineHeight = 14.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Local bypass button to clear tunnels
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (activeProfile == null) Color(0xFFE8F0FE) else CardSlate
            ),
            border = BorderStroke(
                width = 1.5.dp,
                color = if (activeProfile == null) NeonGreen else BorderSlate
            ),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.deactivateActiveProfile() }
                .testTag("profile_card_direct")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (activeProfile == null) NeonGreen.copy(alpha = 0.15f) else BorderSlate),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.VerifiedUser,
                        contentDescription = null,
                        tint = if (activeProfile == null) NeonGreen else MutedText,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Direct Local Router Only (Direct Client)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MainText
                    )
                    Text(
                        text = "No remote tunneling capsule. Direct internet with only blackhole blocks active.",
                        fontSize = 11.sp,
                        color = MutedText
                    )
                }
                if (activeProfile == null) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = "Active",
                        tint = NeonGreen,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Profiles lists
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("vpn_profiles_list"),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(profiles, key = { it.id }) { profile ->
                val isActive = activeProfile?.id == profile.id
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isActive) Color(0xFFE8F0FE) else CardSlate
                    ),
                    border = BorderStroke(
                        width = 1.5.dp,
                        color = if (isActive) NeonCyan else BorderSlate
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.selectVpnProfile(profile) }
                        .testTag("profile_card_${profile.id}")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (isActive) NeonCyan.copy(alpha = 0.15f) else BorderSlate),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Dns,
                                contentDescription = null,
                                tint = if (isActive) NeonCyan else MutedText,
                                modifier = Modifier.size(20.dp)
                              )
                        }
                        
                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = profile.profileName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = MainText
                            )
                            Text(
                                text = "${profile.serverAddress}:${profile.serverPort}",
                                fontSize = 11.sp,
                                color = MutedText
                            )
                            Text(
                                text = "DNS: ${profile.dnsServer} | MTU: ${profile.mtu} | Tunnel: ${if (profile.splitTunneling) "Split" else "Full"}",
                                fontSize = 10.sp,
                                color = NeonCyan
                            )
                        }

                        if (isActive) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = "Active",
                                tint = NeonCyan,
                                modifier = Modifier.size(22.dp)
                            )
                        } else {
                            IconButton(
                                onClick = { viewModel.deleteVpnProfile(profile) },
                                modifier = Modifier
                                    .testTag("delete_profile_${profile.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Delete,
                                    contentDescription = "Delete Profile",
                                    tint = CrimsonRed,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Add profile dialog popup window
    if (displayAddDialog) {
        Dialog(onDismissRequest = { displayAddDialog = false }) {
            var name by remember { mutableStateOf("") }
            var host by remember { mutableStateOf("") }
            var portStr by remember { mutableStateOf("443") }
            var dns by remember { mutableStateOf("1.1.1.1") }
            var mtuStr by remember { mutableStateOf("1400") }
            var username by remember { mutableStateOf("") }
            var secret by remember { mutableStateOf("") }
            var isSplit by remember { mutableStateOf(true) }

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardSlate),
                border = BorderStroke(1.dp, BorderSlate),
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .testTag("add_profile_dialog")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "NEW SECURE TUNNEL CONFIG",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeonCyan,
                        letterSpacing = 1.sp
                    )
                    HorizontalDivider(color = BorderSlate)

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Profile Name (e.g. Asia Node)", color = MutedText, fontSize = 12.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MainText, unfocusedTextColor = MainText,
                            focusedBorderColor = NeonCyan, unfocusedBorderColor = BorderSlate
                        ),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_profile_name")
                    )

                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("Server IP or Host Address", color = MutedText, fontSize = 12.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MainText, unfocusedTextColor = MainText,
                            focusedBorderColor = NeonCyan, unfocusedBorderColor = BorderSlate
                        ),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_profile_address")
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = portStr,
                            onValueChange = { portStr = it },
                            label = { Text("Port", color = MutedText, fontSize = 12.sp) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = MainText, unfocusedTextColor = MainText,
                                focusedBorderColor = NeonCyan, unfocusedBorderColor = BorderSlate
                            ),
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("input_profile_port")
                        )
                        OutlinedTextField(
                            value = mtuStr,
                            onValueChange = { mtuStr = it },
                            label = { Text("MTU Buffer Size", color = MutedText, fontSize = 12.sp) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = MainText, unfocusedTextColor = MainText,
                                focusedBorderColor = NeonCyan, unfocusedBorderColor = BorderSlate
                            ),
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("input_profile_mtu")
                        )
                    }

                    OutlinedTextField(
                        value = dns,
                        onValueChange = { dns = it },
                        label = { Text("DNS Forwarder Server", color = MutedText, fontSize = 12.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MainText, unfocusedTextColor = MainText,
                            focusedBorderColor = NeonCyan, unfocusedBorderColor = BorderSlate
                        ),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_profile_dns")
                    )

                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Gateway Username (Credentials)", color = MutedText, fontSize = 12.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MainText, unfocusedTextColor = MainText,
                            focusedBorderColor = NeonCyan, unfocusedBorderColor = BorderSlate
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = secret,
                        onValueChange = { secret = it },
                        label = { Text("Secure Shared Private Secret / Token", color = MutedText, fontSize = 12.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MainText, unfocusedTextColor = MainText,
                            focusedBorderColor = NeonCyan, unfocusedBorderColor = BorderSlate
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Enable Split-tunneling (Optimize App routing)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedText
                        )
                        Switch(
                            checked = isSplit,
                            onCheckedChange = { isSplit = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = NeonCyan,
                                checkedTrackColor = BorderSlate,
                                uncheckedThumbColor = MutedText,
                                uncheckedTrackColor = DarkSlate
                            )
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { displayAddDialog = false },
                            colors = ButtonDefaults.textButtonColors(contentColor = CrimsonRed)
                        ) {
                            Text("CANCEL", fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = {
                                if (name.isNotBlank() && host.isNotBlank()) {
                                    val portVal = portStr.toIntOrNull() ?: 443
                                    val mtuVal = mtuStr.toIntOrNull() ?: 1400
                                    viewModel.addVpnProfile(
                                        VpnProfile(
                                            profileName = name,
                                            serverAddress = host,
                                            serverPort = portVal,
                                            dnsServer = dns,
                                            mtu = mtuVal,
                                            username = username,
                                            sharedSecret = secret,
                                            splitTunneling = isSplit
                                        )
                                    )
                                    displayAddDialog = false
                                } else {
                                    Toast.makeText(context, "Please configure Profile Name and Server Host.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("SAVE TO CORE", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// Formatting Helper Functions
// ==========================================
fun formatSpeed(bytesPerSec: Long): String {
    if (bytesPerSec <= 0) return "0.0 B/s"
    val kb = bytesPerSec / 1024f
    if (kb < 1024) {
        return String.format(Locale.getDefault(), "%.1f KB/s", kb)
    }
    val mb = kb / 1024f
    return String.format(Locale.getDefault(), "%.1f MB/s", mb)
}

fun formatBytes(totalBytes: Long): String {
    if (totalBytes <= 0) return "0.0 B"
    val kb = totalBytes / 1024f
    if (kb < 1024f) {
        return String.format(Locale.getDefault(), "%.1f KB", kb)
    }
    val mb = kb / 1024f
    if (mb < 1024f) {
        return String.format(Locale.getDefault(), "%.1f MB", mb)
    }
    val gb = mb / 1024f
    return String.format(Locale.getDefault(), "%.1f GB", gb)
}

// ==========================================
// New Screen: Pending Access Dialog Requests (No root firewall style)
// ==========================================
@Composable
fun PendingRequestsScreen(viewModel: MainViewModel) {
    val pendingLogs by viewModel.pendingLogs.collectAsStateWithLifecycle()
    val isVpnActive by VpnController.isVpnActive.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "PROMPT ON DEMAND (PENDING CONNECTS)",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = NeonCyan,
            letterSpacing = 1.sp
        )
        Text(
            text = "App Connection Authorizations",
            style = MaterialTheme.typography.titleMedium,
            color = MainText,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "Just like No Root Firewall, when an app attempts to call external IP endpoints, it is held here. Authorize or Deny access below.",
            fontSize = 11.sp,
            color = MutedText,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (!isVpnActive) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = CardSlate),
                border = BorderStroke(1.dp, BorderSlate),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PowerOff,
                        contentDescription = null,
                        tint = CrimsonRed,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Firewall is Sleeping",
                        fontWeight = FontWeight.Bold,
                        color = MainText,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Activate the SafeRoute Firewall at the top to start interception.",
                        color = MutedText,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else if (pendingLogs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = NeonGreen.copy(alpha = 0.6f),
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "All connections resolved",
                        color = MainText,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "No apps are currently pending authorization.",
                        color = MutedText,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(pendingLogs, key = { it.id }) { log ->
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = CardSlate),
                        border = BorderStroke(1.dp, BorderSlate),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                AppIcon(
                                    packageName = log.packageName,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = log.appName,
                                        fontWeight = FontWeight.Bold,
                                        color = MainText,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = log.packageName,
                                        color = MutedText,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(NeonCyan.copy(alpha = 0.1f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = log.protocol,
                                        color = NeonCyan,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            HorizontalDivider(color = BorderSlate, thickness = 0.5.dp)
                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Language,
                                    contentDescription = null,
                                    tint = MutedText,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Destination: ",
                                    color = MutedText,
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = "${log.destinationAddress}:${log.port}",
                                    color = MainText,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = { viewModel.respondToPendingRequest(log, allow = false) },
                                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonRed.copy(alpha = 0.2f)),
                                    border = BorderStroke(1.dp, CrimsonRed),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(vertical = 8.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = null,
                                            tint = CrimsonRed,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "DENY ACCESS",
                                            color = CrimsonRed,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp
                                        )
                                    }
                                }

                                Button(
                                    onClick = { viewModel.respondToPendingRequest(log, allow = true) },
                                    colors = ButtonDefaults.buttonColors(containerColor = NeonGreen.copy(alpha = 0.2f)),
                                    border = BorderStroke(1.dp, NeonGreen),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(vertical = 8.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = NeonGreen,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "ALLOW ACCESS",
                                            color = NeonGreen,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp
                                        )
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

// ==========================================
// New Screen: Real-Time Connection Access Logs History
// ==========================================
@Composable
fun AccessLogsScreen(viewModel: MainViewModel) {
    val logs by viewModel.firewallLogs.collectAsStateWithLifecycle()
    val isVpnActive by VpnController.isVpnActive.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "REAL-TIME TRAFFIC AUDIT",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = NeonCyan,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Connection Log Book",
                    style = MaterialTheme.typography.titleMedium,
                    color = MainText
                )
            }
            if (logs.isNotEmpty()) {
                IconButton(
                    onClick = { viewModel.clearAllLogs() },
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(BorderSlate)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteSweep,
                        contentDescription = "Clear All Logs",
                        tint = CrimsonRed
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (!isVpnActive && logs.isEmpty()) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = CardSlate),
                border = BorderStroke(1.dp, BorderSlate),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MonitorHeart,
                        contentDescription = null,
                        tint = NeonCyan,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Audit Log is Idle",
                        fontWeight = FontWeight.Bold,
                        color = MainText,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Logs will instantly record network attempts once you run the Firewall.",
                        color = MutedText,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = NeonCyan, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Listening for live packet connections...", color = MutedText, fontSize = 12.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(logs, key = { it.id }) { log ->
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = CardSlate.copy(alpha = 0.8f)),
                        border = BorderStroke(0.5.dp, BorderSlate)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            AppIcon(
                                packageName = log.packageName,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(6.dp))
                            )

                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = log.appName,
                                        fontWeight = FontWeight.Bold,
                                        color = MainText,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(if (log.isBlocked) CrimsonRed.copy(alpha = 0.15f) else NeonGreen.copy(alpha = 0.15f))
                                            .padding(horizontal = 4.dp, vertical = 1.dp)
                                    ) {
                                        Text(
                                            text = if (log.isBlocked) "DENIED" else "ALLOWED",
                                            color = if (log.isBlocked) CrimsonRed else NeonGreen,
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Text(
                                    text = "${log.destinationAddress}:${log.port} (${log.protocol})",
                                    color = MutedText,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(java.util.Date(log.timestamp)),
                                    color = MutedText.copy(alpha = 0.7f),
                                    fontSize = 9.sp
                                )
                            }

                            IconButton(
                                onClick = { viewModel.deleteLog(log.id) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Delete",
                                    tint = MutedText,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// Integrated Gateway Monitor Screen (Graph + Profiles)
// ==========================================
@Composable
fun GatewayMonitorScreen(viewModel: MainViewModel) {
    val dlSpeed by VpnController.downloadSpeed.collectAsStateWithLifecycle()
    val ulSpeed by VpnController.uploadSpeed.collectAsStateWithLifecycle()
    val totalCurrentSpeedKbSec = (dlSpeed + ulSpeed) / 1024f
    val speedHistory = remember { mutableStateListOf<Float>() }

    LaunchedEffect(totalCurrentSpeedKbSec) {
        speedHistory.add(totalCurrentSpeedKbSec)
        if (speedHistory.size > 30) {
            speedHistory.removeAt(0)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Section A: Mini Live Speed Wave chart
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = CardSlate),
            border = BorderStroke(1.dp, BorderSlate),
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("GATEWAY COMPOSITE BANDWIDTH", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = MutedText, letterSpacing = 0.5.sp)
                        Text(String.format(Locale.getDefault(), "%.1f KB/s", totalCurrentSpeedKbSec), fontWeight = FontWeight.Black, fontSize = 16.sp, color = NeonCyan)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(NeonCyan))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("D: ${formatSpeed(dlSpeed)}", fontSize = 9.sp, color = MainText)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(NeonGreen))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("U: ${formatSpeed(ulSpeed)}", fontSize = 9.sp, color = MainText)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    if (speedHistory.isEmpty()) return@Canvas
                    val maxVal = (speedHistory.maxOrNull() ?: 10f).coerceAtLeast(10f)
                    val spacing = size.width / (30 - 1).coerceAtLeast(1)
                    val path = Path()
                    val fillPath = Path()

                    speedHistory.forEachIndexed { index, value ->
                        val x = index * spacing
                        val y = size.height - (value / maxVal) * size.height

                        if (index == 0) {
                            path.moveTo(x, y)
                            fillPath.moveTo(x, size.height)
                            fillPath.lineTo(x, y)
                        } else {
                            path.lineTo(x, y)
                            fillPath.lineTo(x, y)
                        }
                        if (index == speedHistory.size - 1) {
                            fillPath.lineTo(x, size.height)
                            fillPath.close()
                        }
                    }

                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(NeonCyan.copy(alpha = 0.25f), Color.Transparent),
                            startY = 0f,
                            endY = size.height
                        )
                    )
                    drawPath(
                        path = path,
                        color = NeonCyan,
                        style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Section B: Dedicated Secure Gateway Server Profiles List
        Box(modifier = Modifier.weight(1f)) {
            SecureProfilesScreen(viewModel)
        }
    }
}
