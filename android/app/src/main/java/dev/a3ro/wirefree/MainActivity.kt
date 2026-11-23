package dev.a3ro.wirefree

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

class MainActivity : ComponentActivity(), SharedPreferences.OnSharedPreferenceChangeListener {
    private val settings by lazy { Settings(this) }
    private var name by mutableStateOf<String?>(null)
    private var muted by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var batteryPrompt by mutableStateOf(false)
    private var startPending by mutableStateOf(false)
    private var scanning by mutableStateOf(false)
    private var resumed = false
    private var pendingStart = false

    private var canDetectCalls by mutableStateOf(false)

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            MIC_REQUEST -> if (granted) requestNotifications() else {
                startPending = false
                error = "Allow microphone access to enable wirefree."
            }
            CALL_REQUEST -> canDetectCalls = granted
            NOTIFICATION_REQUEST -> queueStart()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        settings.prefs.registerOnSharedPreferenceChangeListener(this)
        sync()
        canDetectCalls = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColors else lightColors,
                typography = type) {
                val status = serviceStatus.value
                var forget by remember { mutableStateOf(false) }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth()
                            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp)) {
                            Wordmark()
                            Spacer(Modifier.height(40.dp))
                            val laptop = name
                            if (laptop == null) {
                                PairScreen(scanning, error, ::scan, ::pair)
                            } else {
                                ControlScreen(laptop, status, muted, startPending, error,
                                    { settings.muted = !settings.muted }, ::setEnabled, { forget = true })
                                if (!canDetectCalls && packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
                                    TextButton(onClick = { requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), CALL_REQUEST) },
                                        modifier = Modifier.heightIn(min = 48.dp),
                                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) {
                                        Text("Detect phone calls")
                                    }
                                }
                            }
                        }
                    }
                }
                if (forget) AlertDialog(onDismissRequest = { forget = false },
                    title = { Text("Forget laptop?") },
                    text = { Text("To reconnect, scan or paste a new pairing code from the laptop.") },
                    confirmButton = { TextButton(onClick = {
                        setEnabled(false)
                        settings.forget()
                        forget = false
                    }) { Text("Forget laptop") } },
                    dismissButton = { TextButton(onClick = { forget = false }) { Text("Cancel") } })
                if (batteryPrompt) AlertDialog(onDismissRequest = { batteryPrompt = false },
                    title = { Text("Keep audio connected") },
                    text = { Text("Allow wirefree to keep its connection when the screen is off. " +
                        "The microphone still opens only when the laptop requests it and youâ€™re unmuted.") },
                    confirmButton = { TextButton(onClick = {
                        batteryPrompt = false
                        runCatching { startActivity(Intent(ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName"))) }
                    }) { Text("Continue") } },
                    dismissButton = { TextButton(onClick = { batteryPrompt = false }) { Text("Skip") } })
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == RESUME && settings.enabled && name != null && !serviceStatus.value.running) {
            setEnabled(true)
            intent.action = null
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        resumed = true
        if (pendingStart) startConnection()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onDestroy() {
        settings.prefs.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) = sync()

    private fun sync() {
        name = settings.name
        muted = settings.muted
    }

    private fun scan() {
        scanning = true
        error = null
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode ->
                scanning = false
                if (isDestroyed) return@addOnSuccessListener
                barcode.rawValue?.let(::pair) ?: run { error = "The QR code contains no pairing link." }
            }
            .addOnCanceledListener { scanning = false }
            .addOnFailureListener {
                scanning = false
                error = "QR scanning is unavailable. Paste the pairing link from your laptop."
            }
    }

    private fun pair(text: String) {
        val pairing = runCatching { Pairing.parse(text) }.getOrElse {
            error = "Use the pairing link shown by wirefree on your laptop."
            return
        }
        try {
            settings.save(pairing)
            setEnabled(true)
        } catch (_: Exception) {
            error = "Couldnâ€™t save the pairing. Try again."
        } finally {
            pairing.key.fill(0)
        }
    }

    private fun setEnabled(enabled: Boolean) {
        error = null
        if (!enabled) {
            pendingStart = false
            startPending = false
            settings.enabled = false
            stopService(Intent(this, WirefreeService::class.java))
            cancelResumeNotification()
            return
        }
        if (startPending || serviceStatus.value.running) return
        startPending = true
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            requestNotifications()
        } else if (resumed) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_REQUEST)
        } else {
            queueStart()
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED && !settings.prefs.getBoolean("askedNotifications", false)) {
            settings.prefs.edit().putBoolean("askedNotifications", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
        } else queueStart()
    }

    private fun queueStart() {
        pendingStart = true
        if (resumed) startConnection()
    }

    private fun startConnection() {
        pendingStart = false
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_REQUEST)
            return
        }
        settings.enabled = true
        try {
            startForegroundService(Intent(this, WirefreeService::class.java))
            val power = getSystemService(PowerManager::class.java)
            if (!power.isIgnoringBatteryOptimizations(packageName) &&
                !settings.prefs.getBoolean("askedBattery", false)) {
                settings.prefs.edit().putBoolean("askedBattery", true).apply()
                batteryPrompt = true
            }
        } catch (_: RuntimeException) {
            settings.enabled = false
            error = "Couldnâ€™t start wirefree. Turn the connection on again."
        } finally {
            startPending = false
        }
    }
}

private const val MIC_REQUEST = 1
private const val CALL_REQUEST = 2
private const val NOTIFICATION_REQUEST = 3

private val lightColors = lightColorScheme(
    primary = Color(0xFF12645F), onPrimary = Color.White,
    background = Color(0xFFF4F7F8), onBackground = Color(0xFF162B34),
    surface = Color(0xFFF4F7F8), onSurface = Color(0xFF162B34),
    surfaceVariant = Color(0xFFDBE7EA), onSurfaceVariant = Color(0xFF40535B),
    outline = Color(0xFF647982), outlineVariant = Color(0xFFCCD9DE),
    secondaryContainer = Color(0xFFDFECEF), onSecondaryContainer = Color(0xFF243E48),
    error = Color(0xFFA52630), onError = Color.White
)

private val darkColors = darkColorScheme(
    primary = Color(0xFF9CE4DC), onPrimary = Color(0xFF103B39),
    background = Color(0xFF101B22), onBackground = Color(0xFFE5F1F2),
    surface = Color(0xFF101B22), onSurface = Color(0xFFE5F1F2),
    surfaceVariant = Color(0xFF233640), onSurfaceVariant = Color(0xFFB9CDD2),
    outline = Color(0xFF8FA7AF), outlineVariant = Color(0xFF34505D),
    secondaryContainer = Color(0xFF233640), onSecondaryContainer = Color(0xFFCDE3E7),
    error = Color(0xFFB2313E), onError = Color.White, errorContainer = Color(0xFF70242C),
    onErrorContainer = Color(0xFFFFD9DB)
)

private val type = Typography(
    headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-1.25).sp,
        fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 23.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp,
        lineHeight = 18.sp, letterSpacing = 0.3.sp)
)

@Composable
private fun Wordmark() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(painterResource(R.drawable.ic_notification), null,
            Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        Text("wirefree", style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun PairScreen(scanning: Boolean, error: String?, scan: () -> Unit, pair: (String) -> Unit) {
    var showPaste by remember { mutableStateOf(false) }
    var uri by remember { mutableStateOf("") }
    StatusMark("Not paired", false)
    Spacer(Modifier.height(12.dp))
    Text("Pair your laptop", style = MaterialTheme.typography.headlineLarge)
    Spacer(Modifier.height(12.dp))
    Text("Open wirefree on Windows and scan its pairing QR code. Keep Tailscale on on both devices.",
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(32.dp))
    Button(onClick = scan, enabled = !scanning, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text(if (scanning) "Opening scannerâ€¦" else "Scan pairing code")
    }
    if (!showPaste) {
        TextButton(onClick = { showPaste = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Paste pairing link")
        }
    } else {
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(uri, { uri = it }, Modifier.fillMaxWidth(), label = { Text("Pairing link") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), isError = error != null)
        TextButton(onClick = { pair(uri) }, enabled = uri.isNotBlank(),
            modifier = Modifier.heightIn(min = 48.dp)) { Text("Pair laptop") }
    }
    ScreenError(error)
}

@Composable
private fun ControlScreen(
    laptop: String, status: Status, muted: Boolean, starting: Boolean, error: String?,
    toggle: () -> Unit, enable: (Boolean) -> Unit, forget: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    StatusMark(when {
        status.online -> "Connected to $laptop"
        status.running || starting -> "Looking for $laptop"
        else -> "Connection off"
    }, status.online)
    Spacer(Modifier.height(12.dp))
    Text(laptop, style = MaterialTheme.typography.headlineLarge)
    Spacer(Modifier.height(24.dp))
    status.priority?.let {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(colors.secondaryContainer).padding(16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }) {
            Text("Phone has priority", fontWeight = FontWeight.SemiBold, color = colors.onSecondaryContainer)
            Text(it, color = colors.onSecondaryContainer)
        }
        Spacer(Modifier.height(20.dp))
    }
    Text(when {
        !status.online -> "Laptop audio route unavailable"
        status.earpods -> "Laptop audio â†’ EarPods"
        else -> "EarPods not connected Â· laptop uses its own speaker"
    }, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(6.dp))
    Text("Mic: ${if (muted) "muted" else status.mic ?: "off"}", color = colors.onSurfaceVariant)
    Spacer(Modifier.height(28.dp))
    Button(onClick = toggle,
        modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp).semantics {
            contentDescription = if (muted) "Unmute microphone" else "Mute microphone"
            stateDescription = if (muted) "Muted" else "Unmuted"
        },
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (muted) colors.error else colors.primary,
            contentColor = if (muted) colors.onError else colors.onPrimary),
        contentPadding = PaddingValues(16.dp)) {
        Icon(painterResource(if (muted) R.drawable.ic_muted else R.drawable.ic_mic),
            null, Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(if (muted) "Muted" else "Mute mic", style = MaterialTheme.typography.titleLarge)
            if (muted) Text("Tap to unmute", style = MaterialTheme.typography.bodySmall)
        }
    }
    Spacer(Modifier.height(28.dp))
    HorizontalDivider(color = colors.outlineVariant)
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Enable connection", Modifier.weight(1f))
        Switch(checked = status.running || starting, onCheckedChange = enable, enabled = !starting,
            modifier = Modifier.semantics { contentDescription = "Enable connection" })
    }
    ScreenError(error)
    TextButton(onClick = forget, modifier = Modifier.heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) { Text("Forget laptop") }
}

@Composable
private fun ScreenError(error: String?) {
    if (error == null) return
    Spacer(Modifier.height(12.dp))
    Text(error, color = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun StatusMark(label: String, connected: Boolean) {
    val colors = MaterialTheme.colorScheme
    val dot by animateColorAsState(if (connected) colors.primary else colors.outline,
        tween(180), label = "Connection status")
    Row(Modifier.clip(RoundedCornerShape(16.dp)).background(colors.secondaryContainer)
        .padding(horizontal = 12.dp, vertical = 7.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(7.dp).background(dot, CircleShape))
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSecondaryContainer)
    }
}
