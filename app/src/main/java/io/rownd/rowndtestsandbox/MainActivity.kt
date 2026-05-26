package io.rownd.rowndtestsandbox

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.rownd.android.Rownd
import io.rownd.android.RowndSignInHint
import io.rownd.android.RowndSignInOptions
import io.rownd.android.RowndSignOutScope
import io.rownd.android.util.SuperTokensSessionBridge
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colors = lightColors(
                    primary = Ink,
                    primaryVariant = Ink,
                    secondary = SoftSlate,
                    background = AppBackground,
                    surface = Color.White,
                    onPrimary = Color.White,
                    onSecondary = Ink,
                    onBackground = Ink,
                    onSurface = Ink,
                )
            ) {
                val state by Rownd.state.collectAsState()
                val scope = rememberCoroutineScope()
                val scrollState = rememberScrollState()
                var sessionExists by remember { mutableStateOf("unknown") }
                var accessToken by remember { mutableStateOf("not loaded") }
                var userDataResult by remember { mutableStateOf("not loaded") }
                var updateValue by remember { mutableStateOf("Ada") }
                var refreshResult by remember { mutableStateOf("not run") }
                var scenarioStatus by remember { mutableStateOf("idle") }

                Surface(
                    color = MaterialTheme.colors.background,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(16.dp),
                    ) {
                        HeroCard()
                        StatusCard(
                            initialized = state.isInitialized,
                            authenticated = state.auth.isAuthenticated,
                            accessTokenValid = state.auth.isAccessTokenValid,
                            sessionExists = sessionExists,
                            scenarioStatus = scenarioStatus,
                        )

                        if (state.auth.isAuthenticated) {
                            PostLoginCard(
                                accessToken = accessToken,
                                userDataResult = userDataResult,
                                refreshResult = refreshResult,
                                updateValue = updateValue,
                                onUpdateValueChange = { updateValue = it },
                                onCheckSession = {
                                    scope.launch {
                                        scenarioStatus = "session_check_requested"
                                        sessionExists = SuperTokensSessionBridge
                                            .doesSessionExist(applicationContext)
                                            .toString()
                                        accessToken = Rownd.getAccessToken()
                                            ?.take(24)
                                            ?.let { "$it..." }
                                            ?: "null"
                                        scenarioStatus = "session_check_complete"
                                    }
                                },
                                onForceRefresh = {
                                    scope.launch {
                                        scenarioStatus = "token_refresh_requested"
                                        refreshResult = SuperTokensSessionBridge
                                            .attemptRefresh(applicationContext)
                                            .toString()
                                        scenarioStatus = "token_refresh_complete"
                                    }
                                },
                                onFetchUserData = {
                                    scope.launch {
                                        scenarioStatus = "user_data_requested"
                                        val user = Rownd.user.refresh().await()
                                        userDataResult = user?.data?.toString() ?: "null"
                                        scenarioStatus = "user_data_loaded"
                                    }
                                },
                                onUpdateUserData = {
                                    scope.launch {
                                        scenarioStatus = "profile_update_requested"
                                        val user = Rownd.user.set("maestro_test_field", updateValue).await()
                                        userDataResult = user?.data?.toString() ?: "null"
                                        scenarioStatus = "profile_update_complete"
                                    }
                                },
                                onManageAccount = {
                                    scenarioStatus = "manage_account_requested"
                                    Rownd.manageAccount()
                                },
                                onSignOut = {
                                    scenarioStatus = "signed_out"
                                    Rownd.signOut()
                                },
                                onSignOutAll = {
                                    scenarioStatus = "sign_out_all_requested"
                                    Rownd.signOut(RowndSignOutScope.All)
                                },
                            )
                        } else {
                            LoginCard(
                                googleEnabled = state.appConfig.config.hub.auth.signInMethods.google.enabled,
                                guestEnabled = state.appConfig.config.hub.auth.signInMethods.anonymous.enabled,
                                onOpenAuth = {
                                    scenarioStatus = "modal_open_requested"
                                    Rownd.requestSignIn(RowndSignInOptions())
                                },
                                onOneTap = {
                                    scenarioStatus = "one_tap_requested"
                                    Rownd.requestSignIn(RowndSignInHint.OneTap)
                                },
                                onGuest = {
                                    scenarioStatus = "guest_requested"
                                    Rownd.requestSignIn(RowndSignInHint.Guest)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroCard() {
    SandboxCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "All authentication methods example",
                color = MutedText,
                style = MaterialTheme.typography.subtitle2,
            )
            Text(
                text = "Try the Hub auth flows",
                style = MaterialTheme.typography.h4,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "This Android sandbox loads the local SuperTokens backend and Rownd Hub to test sign-in, guest login, token refresh, profile updates, and sign out.",
                color = MutedText,
                style = MaterialTheme.typography.body2,
            )
        }
    }
}

@Composable
private fun StatusCard(
    initialized: Boolean,
    authenticated: Boolean,
    accessTokenValid: Boolean,
    sessionExists: String,
    scenarioStatus: String,
) {
    SandboxCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusRow("Host", if (initialized) "ready" else "loading")
            StatusRow("Auth", if (authenticated) "signed_in" else "signed_out")
            StatusRow("Example", "all-authentication-methods-android")
            StatusRow("Scenario", scenarioStatus)
            StatusRow("Access token", if (accessTokenValid) "valid" else "invalid")
            StatusRow("SuperTokens session", sessionExists)
        }
    }
}

@Composable
private fun LoginCard(
    googleEnabled: Boolean,
    guestEnabled: Boolean,
    onOpenAuth: () -> Unit,
    onOneTap: () -> Unit,
    onGuest: () -> Unit,
) {
    SandboxCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Flows", style = MaterialTheme.typography.h5, fontWeight = FontWeight.SemiBold)
            Text(
                text = "Use these controls to launch each enabled Hub auth method.",
                color = MutedText,
                style = MaterialTheme.typography.body2,
            )
            FlowButton("Open Rownd auth UI", onClick = onOpenAuth)
            if (googleEnabled) {
                FlowButton("Show One Tap", onClick = onOneTap)
            }
            if (guestEnabled) {
                FlowButton("Continue as guest", style = FlowButtonStyle.Secondary, onClick = onGuest)
            }
        }
    }
}

@Composable
private fun PostLoginCard(
    accessToken: String,
    userDataResult: String,
    refreshResult: String,
    updateValue: String,
    onUpdateValueChange: (String) -> Unit,
    onCheckSession: () -> Unit,
    onForceRefresh: () -> Unit,
    onFetchUserData: () -> Unit,
    onUpdateUserData: () -> Unit,
    onManageAccount: () -> Unit,
    onSignOut: () -> Unit,
    onSignOutAll: () -> Unit,
) {
    SandboxCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Post-login page", style = MaterialTheme.typography.h5, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "Verify the SuperTokens session, inspect user data, and exercise account actions.",
                        color = MutedText,
                        style = MaterialTheme.typography.body2,
                    )
                }
                FlowButton("Profile", style = FlowButtonStyle.Compact, onClick = onManageAccount)
            }

            FlowButton("Check session", onClick = onCheckSession)
            FlowButton("Force token refresh", onClick = onForceRefresh)
            FlowButton("Fetch user data", onClick = onFetchUserData)

            OutlinedTextField(
                value = updateValue,
                onValueChange = onUpdateValueChange,
                label = { Text("Test field value") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            FlowButton("Update user data", onClick = onUpdateUserData)
            FlowButton("Sign out", style = FlowButtonStyle.Secondary, onClick = onSignOut)
            FlowButton("Sign out all sessions", style = FlowButtonStyle.Secondary, onClick = onSignOutAll)

            ResultBox(
                text = "Access token: $accessToken\nRefresh result: $refreshResult\nUser data: $userDataResult",
            )
        }
    }
}

@Composable
private fun SandboxCard(content: @Composable () -> Unit) {
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(16.dp),
        elevation = 10.dp,
        border = BorderStroke(1.dp, CardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            content()
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Text("$label:", fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.width(8.dp))
        SelectionContainer(modifier = Modifier.weight(1f)) {
            Text(value)
        }
    }
}

private enum class FlowButtonStyle {
    Primary,
    Secondary,
    Compact,
}

@Composable
private fun FlowButton(
    title: String,
    style: FlowButtonStyle = FlowButtonStyle.Primary,
    onClick: () -> Unit,
) {
    val isPrimary = style == FlowButtonStyle.Primary || style == FlowButtonStyle.Compact
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        elevation = ButtonDefaults.elevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = if (isPrimary) Ink else SoftSlate,
            contentColor = if (isPrimary) Color.White else Ink,
        ),
        modifier = if (style == FlowButtonStyle.Compact) Modifier else Modifier.fillMaxWidth(),
    ) {
        Text(
            text = title,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(
                horizontal = if (style == FlowButtonStyle.Compact) 4.dp else 12.dp,
                vertical = if (style == FlowButtonStyle.Compact) 2.dp else 6.dp,
            ),
        )
    }
}

@Composable
private fun ResultBox(text: String) {
    SelectionContainer {
        Text(
            text = text,
            color = ConsoleText,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.caption,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(ConsoleBackground)
                .border(1.dp, ConsoleBorder, RoundedCornerShape(12.dp))
                .padding(12.dp),
        )
    }
}

private val AppBackground = Color(0xFFF6F7FB)
private val CardBorder = Color(0xFFDBE2EA)
private val ConsoleBackground = Color(0xFF0F172A)
private val ConsoleBorder = Color(0xFF1E293B)
private val ConsoleText = Color(0xFFE2E8F0)
private val Ink = Color(0xFF111827)
private val MutedText = Color(0xFF64748B)
private val SoftSlate = Color(0xFFE5E7EB)
