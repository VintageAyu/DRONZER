package com.example.dronzer.ui

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.example.dronzer.R
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun AuthenticationScreen(onLoginSuccess: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("dronzer_config", Context.MODE_PRIVATE) }
    
    val savedUser = remember { prefs.getString("auth_user", "admin") ?: "admin" }
    val savedPass = remember { prefs.getString("auth_pass", "admin") ?: "admin" }
    val is2faEnabled = remember { prefs.getBoolean("auth_2fa", false) }
    // Defaulting biometric to false as requested
    val isBiometricEnabled = remember { prefs.getBoolean("biometric_enabled", false) }

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    val bloodRed = Color(0xFFFF1111)

    val animatedText by produceState(initialValue = "", key1 = Unit) {
        val fullText = "SYSTEM ACCESS AUTHORIZATION"
        for (i in 1..fullText.length) {
            value = fullText.substring(0, i)
            delay(100)
        }
    }

    fun handleBiometricAuth() {
        if (!isBiometricEnabled) return
        
        if (context !is FragmentActivity) {
            onLoginSuccess() // Fallback
            return
        }
        val executor = Executors.newSingleThreadExecutor()
        val biometricPrompt = BiometricPrompt(context, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    context.runOnUiThread {
                        onLoginSuccess()
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("System Authentication")
            .setSubtitle("Authenticate using your registered biometric")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        HexagonalBrickBackground(baseColor = Color(0xFF7F0000))
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(modifier = Modifier.size(120.dp), contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(id = R.drawable.ic_logo),
                    contentDescription = "App Logo",
                    modifier = Modifier.size(100.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Text(
                text = animatedText,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 18.sp,
                    color = bloodRed,
                    fontWeight = FontWeight.Bold,
                    shadow = Shadow(
                        color = bloodRed.copy(alpha = 0.8f),
                        blurRadius = 15f
                    )
                )
            )
            
            Spacer(modifier = Modifier.height(48.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2B0000).copy(alpha = 0.6f))
                    .border(1.5.dp, bloodRed.copy(alpha = 0.6f), MaterialTheme.shapes.extraSmall)
                    .padding(24.dp)
            ) {
                TerminalTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = "LOGN:",
                    accentColor = bloodRed
                )

                Spacer(modifier = Modifier.height(24.dp))

                TerminalTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = "PASS:",
                    visualTransformation = if (showPassword) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                    accentColor = bloodRed,
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "Toggle password visibility",
                                tint = bloodRed,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                )

                if (isBiometricEnabled) {
                    Spacer(modifier = Modifier.height(32.dp))

                    // Fingerprint Visual Logo
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(
                            onClick = { handleBiometricAuth() },
                            modifier = Modifier
                                .size(70.dp)
                                .border(1.dp, bloodRed.copy(alpha = 0.3f), MaterialTheme.shapes.medium)
                                .background(bloodRed.copy(alpha = 0.05f), MaterialTheme.shapes.medium)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Fingerprint,
                                contentDescription = "Biometric Login",
                                tint = bloodRed,
                                modifier = Modifier.size(45.dp)
                            )
                        }
                    }
                }

                if (error.isNotEmpty()) {
                    Text(
                        text = " $error",
                        color = Color(0xFFFF5252),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = {
                        if (username == savedUser && password == savedPass) {
                            if (is2faEnabled && isBiometricEnabled) {
                                val biometricManager = BiometricManager.from(context)
                                val canAuth = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                                if (canAuth == BiometricManager.BIOMETRIC_SUCCESS) {
                                    handleBiometricAuth()
                                } else {
                                    onLoginSuccess()
                                }
                            } else {
                                onLoginSuccess()
                            }
                        } else {
                            error = "ACCESS DENIED"
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = bloodRed.copy(alpha = 0.15f),
                        contentColor = bloodRed
                    ),
                    shape = MaterialTheme.shapes.extraSmall,
                    border = androidx.compose.foundation.BorderStroke(1.dp, bloodRed.copy(alpha = 0.5f))
                ) {
                    Text("INITIALIZE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun TerminalTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    accentColor: Color,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = label,
                color = accentColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 12.dp)
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = TextStyle(
                    color = accentColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp,
                    letterSpacing = 2.sp
                ),
                modifier = Modifier.weight(1f),
                singleLine = true,
                visualTransformation = visualTransformation,
                cursorBrush = SolidColor(accentColor)
            )
            if (trailingIcon != null) {
                trailingIcon()
            }
        }
        HorizontalDivider(
            color = accentColor.copy(alpha = 0.3f),
            thickness = 1.dp,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
fun HexagonalBrickBackground(baseColor: Color = Color(0xFF00B8D4)) {
    val infiniteTransition = rememberInfiniteTransition(label = "hex_bg")
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "flow_progress"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val hexRadius = 40.dp.toPx()
        val hSpacing = hexRadius * 1.5f
        val vSpacing = hexRadius * 1.732f
        
        val cols = (size.width / hSpacing).toInt() + 2
        val rows = (size.height / vSpacing).toInt() + 2
        
        for (r in 0..rows) {
            for (c in 0..cols) {
                val x = c * hSpacing
                val y = r * vSpacing + (if (c % 2 != 0) vSpacing / 2f else 0f)
                
                val normalizedDist = (c.toFloat() / cols + r.toFloat() / rows) / 2f
                var p = (progress - normalizedDist) % 1f
                if (p < 0) p += 1f
                
                val intensity = if (p < 0.3f) (1f - p / 0.3f) else 0f
                
                val baseAlpha = 0.1f
                val pulseAlpha = intensity * 0.5f
                val color = baseColor.copy(alpha = baseAlpha + pulseAlpha)
                
                drawSharpHexagon(Offset(x, y), hexRadius * 0.95f, color)
            }
        }
    }
}

private fun DrawScope.drawSharpHexagon(center: Offset, radius: Float, color: Color) {
    val path = Path().apply {
        for (i in 0..5) {
            val angleDeg = 60 * i
            val angleRad = Math.toRadians(angleDeg.toDouble())
            val px = center.x + radius * cos(angleRad).toFloat()
            val py = center.y + radius * sin(angleRad).toFloat()
            if (i == 0) moveTo(px, py) else lineTo(px, py)
        }
        close()
    }
    drawPath(path, color)
    drawPath(
        path = path,
        color = color.copy(alpha = (color.alpha * 1.5f).coerceAtMost(1f)),
        style = Stroke(width = 1.5.dp.toPx())
    )
}
