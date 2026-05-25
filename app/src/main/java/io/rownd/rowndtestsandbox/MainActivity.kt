package io.rownd.rowndtestsandbox

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.databinding.DataBindingUtil
import io.rownd.android.Rownd
import io.rownd.android.RowndSignInHint
import io.rownd.android.RowndSignInOptions
import io.rownd.android.RowndSignOutScope
import io.rownd.android.util.SuperTokensSessionBridge
import io.rownd.rowndtestsandbox.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding: ActivityMainBinding =
            DataBindingUtil.setContentView(this, R.layout.activity_main)

        binding.lifecycleOwner = this
        binding.rownd = Rownd

        val composeView = findViewById<ComposeView>(R.id.compose_view)
        composeView.setContent {
            val state = Rownd.state.collectAsState()
            val scope = rememberCoroutineScope()
            val scrollState = rememberScrollState()
            val sessionExists = remember { mutableStateOf("unknown") }
            val accessToken = remember { mutableStateOf("not loaded") }
            val userDataResult = remember { mutableStateOf("not loaded") }
            val updateValue = remember { mutableStateOf("Ada") }
            val refreshResult = remember { mutableStateOf("not run") }

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
                    Text("Rownd SuperTokens Sandbox")
                    Text("Initialized: ${state.value.isInitialized}")
                    Text("Authenticated: ${state.value.auth.isAuthenticated}")
                    Text("Valid access token: ${state.value.auth.isAccessTokenValid}")
                    Text("SuperTokens session exists: ${sessionExists.value}")
                    Text("Access token: ${accessToken.value}")
                    Text("User data: ${userDataResult.value}")
                    Text("Refresh result: ${refreshResult.value}")

                    Button(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        onClick = {
                            if (state.value.auth.isAuthenticated) {
                                Rownd.signOut()
                            } else {
                                Rownd.requestSignIn(RowndSignInOptions())
                            }
                        },
                    ) {
                        Text(if (state.value.auth.isAuthenticated) "Sign out" else "Sign in")
                    }

                    if (!state.value.auth.isAuthenticated && state.value.appConfig.config.hub.auth.signInMethods.google.enabled) {
                        Button(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            onClick = { Rownd.requestSignIn(RowndSignInHint.OneTap) },
                        ) {
                            Text("Show One Tap")
                        }
                    }

                    if (!state.value.auth.isAuthenticated && state.value.appConfig.config.hub.auth.signInMethods.anonymous.enabled) {
                        Button(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            onClick = { Rownd.requestSignIn(RowndSignInHint.Guest) },
                        ) {
                            Text("Sign in as a guest")
                        }
                    }

                    Button(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        onClick = {
                            scope.launch {
                                sessionExists.value = SuperTokensSessionBridge
                                    .doesSessionExist(applicationContext)
                                    .toString()
                                accessToken.value = Rownd.getAccessToken()
                                    ?.take(24)
                                    ?.let { "$it..." }
                                    ?: "null"
                            }
                        },
                    ) {
                        Text("Check session")
                    }

                    Button(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        onClick = {
                            scope.launch {
                                refreshResult.value = SuperTokensSessionBridge
                                    .attemptRefresh(applicationContext)
                                    .toString()
                            }
                        },
                    ) {
                        Text("Force token refresh")
                    }

                    Button(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        onClick = {
                            scope.launch {
                                val user = Rownd.user.refresh().await()
                                userDataResult.value = user?.data?.toString() ?: "null"
                            }
                        },
                    ) {
                        Text("Fetch user data")
                    }

                    TextField(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        value = updateValue.value,
                        onValueChange = { updateValue.value = it },
                        label = { Text("Test field value") },
                    )

                    Button(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        onClick = {
                            scope.launch {
                                val user = Rownd.user.set("maestro_test_field", updateValue.value).await()
                                userDataResult.value = user?.data?.toString() ?: "null"
                            }
                        },
                    ) {
                        Text("Update user data")
                    }

                    Button(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        onClick = { Rownd.manageAccount() },
                    ) {
                        Text("Edit profile")
                    }

                    Button(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        onClick = { Rownd.signOut(RowndSignOutScope.All) },
                    ) {
                        Text("Sign out all sessions")
                    }
                }
            }
        }
    }
}
