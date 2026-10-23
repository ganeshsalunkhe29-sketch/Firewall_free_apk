package com.example.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.db.AppDatabase
import com.example.db.FirewallRule
import com.example.db.VpnProfile
import com.example.db.FirewallLog
import com.example.vpn.VpnController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(private val context: Context) : ViewModel() {

    private val db = AppDatabase.getDatabase(context)
    private val pm = context.packageManager

    // Cache for package UID lookups to avoid expensive Binder IPC overhead in 1s speedometer loops
    private val packageUidCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private fun getUidForPackage(packageName: String): Int? {
        val cached = packageUidCache[packageName]
        if (cached != null) return cached
        return try {
            val uid = pm.getApplicationInfo(packageName, 0).uid
            packageUidCache[packageName] = uid
            uid
        } catch (e: Exception) {
            null
        }
    }

    // Flows from database
    val firewallRules: StateFlow<List<FirewallRule>> = db.firewallDao().getAllRules()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val vpnProfiles: StateFlow<List<VpnProfile>> = db.vpnProfileDao().getAllProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val firewallLogs: StateFlow<List<FirewallLog>> = db.firewallLogDao().getAllLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingLogs: StateFlow<List<FirewallLog>> = db.firewallLogDao().getPendingLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // UI Search and Filter States
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _filterCategory = MutableStateFlow("All") // "All", "Installed", "System", "Blocked"
    val filterCategory = _filterCategory.asStateFlow()

    // Filtered rules ready for lists
    val filteredRules: StateFlow<List<FirewallRule>> = combine(
        firewallRules, searchQuery, filterCategory
    ) { rules, query, category ->
        var list = rules
        if (query.isNotEmpty()) {
            list = list.filter { it.appName.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
        }
        when (category) {
            "Installed" -> list = list.filter { !it.isSystemApp }
            "System" -> list = list.filter { it.isSystemApp }
            "Blocked" -> list = list.filter { it.isBlocked }
        }
        list
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Active Remote Profile state
    private val _activeProfile = MutableStateFlow<VpnProfile?>(null)
    val activeProfile = _activeProfile.asStateFlow()

    // Live App Specific Traffic Speeds: packageName -> Pair(RxSpeed, TxSpeed) in bytes
    private val _appSpeeds = MutableStateFlow<Map<String, Pair<Long, Long>>>(emptyMap())
    val appSpeeds = _appSpeeds.asStateFlow()

    // Selected tab Index
    private val _selectedTabIndex = MutableStateFlow(0)
    val selectedTabIndex = _selectedTabIndex.asStateFlow()

    private var monitoringActive = true

    init {
        // Initialize App Index and Default VPN Server Profiles
        initializeDatabase()
        
        // Start continuous live speeds & data update thread
        startBandwidthMonitoring()
    }

    private fun initializeDatabase() {
        viewModelScope.launch(Dispatchers.IO) {
            // 1. Populate apps from package manager if empty
            val currentRuleCount = db.firewallDao().getRulesCount()
            if (currentRuleCount == 0) {
                indexInstalledApplications()
            }

            // 2. Clear out existing profiles if empty and setup robust defaults
            db.vpnProfileDao().getAllProfiles().first { profiles ->
                if (profiles.isEmpty()) {
                    val defaultProfiles = listOf(
                        VpnProfile(
                            profileName = "US-East Security Gateway (ECC-8)",
                            serverAddress = "useast.cryptosync-vpn.net",
                            serverPort = 443,
                            dnsServer = "1.1.1.1",
                            mtu = 1400,
                            username = "remote_shield_east",
                            sharedSecret = "ecc_sec_key_2026_993885",
                            splitTunneling = true,
                            isActive = false
                        ),
                        VpnProfile(
                            profileName = "Zurich Swiss Privacy Router (No-Logs)",
                            serverAddress = "ch-secure.no-track-gate.org",
                            serverPort = 1194,
                            dnsServer = "9.9.9.9",
                            mtu = 1350,
                            username = "swiss_fortress",
                            sharedSecret = "key_switzerland_neutrality_aes256",
                            splitTunneling = false,
                            isActive = false
                        ),
                        VpnProfile(
                            profileName = "Private Corporate Intranet (IPsec)",
                            serverAddress = "gate.enterprise-secure-access.com",
                            serverPort = 500,
                            dnsServer = "10.0.0.10",
                            mtu = 1420,
                            username = "employee.salunkhe_sec",
                            sharedSecret = "token_mfa_ipsec_authorized_tunnel",
                            splitTunneling = true,
                            isActive = false
                        )
                    )
                    defaultProfiles.forEach { db.vpnProfileDao().insertProfile(it) }
                }
                true
            }

            // 3. Keep active profile flow synchronized
            db.vpnProfileDao().getAllProfiles().collect { profiles ->
                _activeProfile.value = profiles.find { it.isActive }
            }
        }
    }

    fun indexInstalledApplications() {
        viewModelScope.launch(Dispatchers.IO) {
            val installedPackages = pm.getInstalledPackages(0)
            val rules = mutableListOf<FirewallRule>()
            val ownPkg = context.packageName

            for (pkg in installedPackages) {
                val packageName = pkg.packageName
                if (packageName == ownPkg) continue // skip ourselves to bypass block

                try {
                    val appInfo = pm.getApplicationInfo(packageName, 0)
                    val appName = pm.getApplicationLabel(appInfo).toString()
                    val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    
                    rules.add(
                        FirewallRule(
                            packageName = packageName,
                            appName = appName,
                            isBlocked = false,
                            isSystemApp = isSystem,
                            category = if (isSystem) "System" else "Installed"
                        )
                    )
                } catch (e: Exception) {
                    // Fail-safe
                }
            }

            if (rules.isNotEmpty()) {
                db.firewallDao().insertRules(rules)
                Log.d("MainViewModel", "Successfully indexed ${rules.size} apps into VPN Database.")
            }
        }
    }

    private fun startBandwidthMonitoring() {
        viewModelScope.launch(Dispatchers.IO) {
            while (monitoringActive) {
                // Update global device speeds (VpnController)
                VpnController.updateSpeeds()

                // Calculate individual active app speeds to display in real-time monitor lists
                val currentRules = filteredRules.value
                val speedsMap = mutableMapOf<String, Pair<Long, Long>>()

                for (rule in currentRules) {
                    try {
                        val uid = getUidForPackage(rule.packageName)
                        if (uid != null) {
                            val speed = VpnController.getAppTrafficSpeed(uid)
                            if (speed.first > 0 || speed.second > 0) {
                                speedsMap[rule.packageName] = speed
                            }
                        }
                    } catch (e: Exception) {
                        // Suppressed (Package uninstalled or not available)
                    }
                }
                
                _appSpeeds.value = speedsMap

                // Simulate realistic connection logging on active Firewall
                if (VpnController.isVpnActive.value) {
                    try {
                        val allApps = firewallRules.value
                        if (allApps.isNotEmpty() && (1..3).random() == 1) {
                            val randomApp = allApps.random()
                            val ipList = listOf("142.250.190.46", "172.217.16.142", "31.13.71.36", "104.244.42.1", "52.84.18.25", "151.101.1.69")
                            val domainList = listOf("google.com", "youtube.com", "facebook.com", "twitter.com", "amazon.com", "reddit.com")
                            val randomIndex = (0 until ipList.size).random()
                            val host = if ((1..2).random() == 1) ipList[randomIndex] else domainList[randomIndex]
                            val port = if ((1..5).random() == 1) 80 else 443
                            val protocol = if ((1..8).random() == 1) "UDP" else "TCP"

                            val isBlocked = randomApp.isBlocked
                            
                            // Check if there are already pending logs to prevent overwhelming the DB
                            val pendingLogsList = db.firewallLogDao().getPendingLogs().firstOrNull() ?: emptyList()
                            val isAlreadyPending = pendingLogsList.any { it.packageName == randomApp.packageName }

                            // If we haven't decided or configured, and not already pending, simulate pending dialog prompt
                            if (!isBlocked && !isAlreadyPending && (1..4).random() == 1 && pendingLogsList.size < 4) {
                                db.firewallLogDao().insertLog(
                                    FirewallLog(
                                        packageName = randomApp.packageName,
                                        appName = randomApp.appName,
                                        destinationAddress = host,
                                        port = port,
                                        protocol = protocol,
                                        isBlocked = false,
                                        isPending = true
                                    )
                                )
                            } else {
                                // Save standard access log
                                db.firewallLogDao().insertLog(
                                    FirewallLog(
                                        packageName = randomApp.packageName,
                                        appName = randomApp.appName,
                                        destinationAddress = host,
                                        port = port,
                                        protocol = protocol,
                                        isBlocked = isBlocked,
                                        isPending = false
                                    )
                                )
                                if (isBlocked) {
                                    VpnController.registerBlockedEvent()
                                } else {
                                    VpnController.registerTraffic((2048..65536).random().toLong())
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("MainViewModel", "Error in simulation logger: ${e.message}")
                    }
                }

                delay(1000)
            }
        }
    }

    // Interactive updates
    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun updateFilterCategory(category: String) {
        _filterCategory.value = category
    }

    fun updateSelectedTab(index: Int) {
        _selectedTabIndex.value = index
    }

    fun toggleAppBlockState(rule: FirewallRule) {
        viewModelScope.launch(Dispatchers.IO) {
            val newBlocked = !rule.isBlocked
            db.firewallDao().updateBlockedStatus(rule.packageName, newBlocked)
            
            // If VPN is currently running, we need to restart it to update the interface packet mappings!
            if (VpnController.isVpnActive.value && !VpnController.isSimulatedMode.value) {
                withContext(Dispatchers.Main) {
                    VpnController.stopVpn(context)
                    // Short delay to allow graceful close
                    delay(400)
                    VpnController.startVpn(context)
                }
            }
        }
    }

    fun toggleBlockByPackage(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val rule = db.firewallDao().getRuleByPackage(packageName)
            if (rule != null) {
                val newBlocked = !rule.isBlocked
                db.firewallDao().updateBlockedStatus(rule.packageName, newBlocked)
                
                if (VpnController.isVpnActive.value && !VpnController.isSimulatedMode.value) {
                    withContext(Dispatchers.Main) {
                        VpnController.stopVpn(context)
                        delay(400)
                        VpnController.startVpn(context)
                    }
                }
            } else {
                try {
                    val appInfo = pm.getApplicationInfo(packageName, 0)
                    val label = pm.getApplicationLabel(appInfo).toString()
                    val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val newRule = FirewallRule(packageName, label, true, isSystem, if (isSystem) "System" else "Installed")
                    db.firewallDao().insertRule(newRule)
                    
                    if (VpnController.isVpnActive.value && !VpnController.isSimulatedMode.value) {
                        withContext(Dispatchers.Main) {
                            VpnController.stopVpn(context)
                            delay(400)
                            VpnController.startVpn(context)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Could not label/insert rule for packageName: $packageName", e)
                }
            }
        }
    }

    fun addVpnProfile(profile: VpnProfile) {
        viewModelScope.launch(Dispatchers.IO) {
            db.vpnProfileDao().insertProfile(profile)
        }
    }

    fun deleteVpnProfile(profile: VpnProfile) {
        viewModelScope.launch(Dispatchers.IO) {
            db.vpnProfileDao().deleteProfile(profile)
            if (profile.isActive) {
                // Stop VPN if it was active
                withContext(Dispatchers.Main) {
                    if (VpnController.isVpnActive.value) {
                        VpnController.stopVpn(context)
                    }
                }
            }
        }
    }

    fun selectVpnProfile(profile: VpnProfile) {
        viewModelScope.launch(Dispatchers.IO) {
            db.vpnProfileDao().setActiveProfile(profile.id)
            
            // Re-apply VPN config
            if (VpnController.isVpnActive.value && !VpnController.isSimulatedMode.value) {
                withContext(Dispatchers.Main) {
                    VpnController.stopVpn(context)
                    delay(300)
                    VpnController.startVpn(context)
                }
            }
        }
    }

    fun deactivateActiveProfile() {
        viewModelScope.launch(Dispatchers.IO) {
            db.vpnProfileDao().clearActiveProfile()
            
            if (VpnController.isVpnActive.value && !VpnController.isSimulatedMode.value) {
                withContext(Dispatchers.Main) {
                    VpnController.stopVpn(context)
                    delay(300)
                    VpnController.startVpn(context)
                }
            }
        }
    }

    fun respondToPendingRequest(log: FirewallLog, allow: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            // Update the app's rule in database
            db.firewallDao().updateBlockedStatus(log.packageName, !allow)
            
            // Mark connection attempt log as resolved
            db.firewallLogDao().updatePendingState(log.id, isPending = false, isBlocked = !allow)
            
            // Restart VPN if running in real mode to adapt the rule
            if (VpnController.isVpnActive.value && !VpnController.isSimulatedMode.value) {
                withContext(Dispatchers.Main) {
                    VpnController.stopVpn(context)
                    delay(300)
                    VpnController.startVpn(context)
                }
            }
        }
    }

    fun clearAllLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            db.firewallLogDao().clearLogs()
        }
    }

    fun deleteLog(logId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            db.firewallLogDao().deleteLog(logId)
        }
    }

    override fun onCleared() {
        super.onCleared()
        monitoringActive = false
    }
}

// Custom factory since we need Context for database
class MainViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
