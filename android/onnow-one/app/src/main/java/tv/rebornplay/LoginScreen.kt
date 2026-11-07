package tv.rebornplay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Sign-in — the same two-column layout as LoginScreen.jsx, Reborn-branded. */
@Composable
fun LoginScreen(onDone: (user: String, pass: String) -> Unit) {
    var user by remember { mutableStateOf("") }; var pass by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val userReq = remember { FocusRequester() }
    FocusOnShow(userReq, Unit)
    val submit = {
        if (!busy && user.isNotBlank() && pass.isNotBlank()) {
            busy = true; err = null
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { Gorilla.login(user.trim(), pass.trim()) } }
                    .onSuccess { onDone(user.trim(), pass.trim()) }
                    .onFailure { err = it.message ?: "Sign-in failed" }
                busy = false
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        ScreenBackdrop()
        val compact = isCompact()
        Row(Modifier.fillMaxSize().padding(horizontal = if (compact) 28.dp else 80.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 20.dp), verticalArrangement = Arrangement.Center) {
                BrandLogo(if (compact) 44.dp else 72.dp)
                Spacer(Modifier.height(if (compact) 16.dp else 30.dp))
                Text("Movies, TV shows,\nand everything\nworth watching.", color = Txt, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = if (compact) 26.sp else 44.sp, lineHeight = if (compact) 30.sp else 48.sp, letterSpacing = (-1.2).sp)
                Spacer(Modifier.height(18.dp))
                Text("Enter the username and password for your Reborn subscription.", color = Txt2, fontFamily = Geist, fontSize = 14.sp, modifier = Modifier.width(380.dp))
                Spacer(Modifier.height(26.dp))
                listOf("Movies & full TV series", "Live TV with a full guide", "Everything loads at sign-in").forEach {
                    Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(6.dp).background(Blue)); Spacer(Modifier.width(12.dp)); Text(it, color = Txt, fontFamily = Geist, fontSize = 13.sp) }
                }
            }
            Spacer(Modifier.width(if (compact) 24.dp else 60.dp))
            Column(Modifier.width(if (compact) 340.dp else 380.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 16.dp), verticalArrangement = Arrangement.Center) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Bg1.copy(alpha = 0.88f)).border(1.dp, Line, RoundedCornerShape(18.dp)).padding(28.dp)) {
                Eyebrow("Step 01"); Spacer(Modifier.height(6.dp))
                Text("Sign in to your account", color = Txt, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = (-0.6).sp)
                Spacer(Modifier.height(4.dp))
                Text("Sign in to access movies, TV shows, Live TV and your library.", color = Txt2, fontFamily = Geist, fontSize = 12.sp)
                Spacer(Modifier.height(22.dp))
                Mono("Username", 10.sp, Txt2); Spacer(Modifier.height(6.dp))
                Field(user, { user = it }, "Enter your username", Icons.Outlined.Person, requester = userReq)
                Spacer(Modifier.height(14.dp))
                Mono("Password", 10.sp, Txt2); Spacer(Modifier.height(6.dp))
                Field(pass, { pass = it }, "Enter your password", Icons.Outlined.Lock, password = !show, onDone = { submit() }, trailing = {
                    Focusable(onClick = { show = !show }) { f -> Icon(if (show) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "Show password", Modifier.size(20.dp), tint = if (f) BlueBright else Txt2) }
                })
                if (err != null) { Spacer(Modifier.height(10.dp)); Text(err!!, color = Color(0xFFFF8A8A), fontFamily = Geist, fontSize = 12.sp) }
                Spacer(Modifier.height(20.dp))
                Focusable(Modifier.fillMaxWidth(), onClick = { submit() }) { f ->
                    Row(Modifier.fillMaxWidth().height(50.dp).clip(CircleShape).background(Brush.horizontalGradient(listOf(if (f) BlueBright else Blue, Color(0xFF3FB0F0)))), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Login, null, Modifier.size(18.dp), tint = Bg0); Spacer(Modifier.width(10.dp))
                        Text(if (busy) "Signing in…" else "Sign in", color = Bg0, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Need an account? Contact support.", color = Txt3, fontFamily = Geist, fontSize = 11.sp); Spacer(Modifier.weight(1f)); Mono("Secure", 9.sp, Blue) }
            }
            }
        }
    }
}

@Composable
fun Field(value: String, onChange: (String) -> Unit, hint: String, icon: ImageVector, password: Boolean = false, requester: FocusRequester? = null, onDone: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    var focused by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(12.dp)).background(Bg0.copy(alpha = 0.7f))
        .border(if (focused) 2.dp else 1.dp, if (focused) Blue else Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp)).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = if (focused) Blue else Txt3); Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, color = Txt3, fontFamily = Geist, fontSize = 15.sp)
            BasicTextField(value, onChange, Modifier.fillMaxWidth().then(if (requester != null) Modifier.focusRequester(requester) else Modifier).onFocusChanged { focused = it.isFocused },
                singleLine = true, textStyle = TextStyle(color = Txt, fontFamily = Geist, fontSize = 15.sp), cursorBrush = SolidColor(Blue),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Text, imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next),
                keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }))
        }
        if (trailing != null) { Spacer(Modifier.width(8.dp)); trailing() }
    }
}
