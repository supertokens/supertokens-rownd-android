# Rownd SDK for Android

The Rownd SDK for Android provides authentication, account and user profile management, deep linking, and more for native Android applications.

Using the Rownd platform, you can easily bring the same authentication that's on your website to your mobile apps. Or if you only authenticate users on your mobile apps, you can streamline authentication using Rownd's passwordless sign-in links, one-time codes, or both, delivered by email or SMS.

Once a user is authenticated, you can retrieve and update their profile information on the fly using native APIs. Leverage Rownd's pre-built mobile app components to give users profile management tools.

## Installation

The SuperTokens Rownd Android SDK is published through JitPack. Add JitPack to your dependency repositories:

```gradle
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url "https://jitpack.io" }
    }
}
```

In your app module's `build.gradle`, add the SDK dependency:

```gradle
dependencies {
    implementation 'com.github.supertokens:supertokens-rownd-android:0.1.0'
}
```

The SDK requires AndroidX and `minSdk 26` or newer. The example app uses Java 17 and Kotlin JVM target 17:

```properties
android.useAndroidX=true
```

```gradle
android {
    defaultConfig {
        minSdk 26
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = '17'
    }
}
```

### ProGuard config

Rownd's Android SDK includes a `consumer-rules.pro` file, which should automatically augment your app's own proguard/r2 config.

If you're using ProGuard to shrink, obfuscate, and/or optimize your app ([and you should!](https://developer.android.com/studio/build/shrink-code)), and you're noticing minification or runtime errors after installing Rownd, you may need to add the following rules to your `proguard-rules.pro` file.

```proguard
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
   static <1>$Companion Companion;
}

# Keep `serializer()` on companion objects (both default and named) of serializable classes.
-if @kotlinx.serialization.Serializable class ** {
   static **$* *;
}
-keepclassmembers class <2>$<3> {
   kotlinx.serialization.KSerializer serializer(...);
}

# Keep `INSTANCE.serializer()` of serializable objects.
-if @kotlinx.serialization.Serializable class ** {
   public static ** INSTANCE;
}
-keepclassmembers class <1> {
   public static <1> INSTANCE;
   kotlinx.serialization.KSerializer serializer(...);
}

# @Serializable and @Polymorphic are used at runtime for polymorphic serialization.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,Annotation,InnerClasses

# Suppress warnings about missing AWT classes (which aren't used in Android)
-dontwarn java.awt.*

# libsodium uses jna
-keep class com.sun.jna.* { *; }
-keepclassmembers class * extends com.sun.jna.* { public *; }

# ViewModel names are used at runtime
-keep public class * extends androidx.lifecycle.ViewModel {*;}
```

## Usage

### 1. Add SDK configuration values

The SDK needs your Rownd app key, your SuperTokens API origin, the SuperTokens API base path, the Rownd Hub base URL, and the Android custom scheme your app accepts. The example app provides them through `BuildConfig` fields and manifest placeholders:

```gradle
android {
    defaultConfig {
        manifestPlaceholders = [rowndDeepLinkScheme: "rowndsupertokens"]

        buildConfigField "String", "ROWND_APP_KEY", '"<YOUR_APP_KEY>"'
        buildConfigField "String", "ROWND_API_DOMAIN", '"<YOUR_API_DOMAIN>"'
        buildConfigField "String", "ROWND_API_BASE_PATH", '"<YOUR_API_BASE_PATH>"'
        buildConfigField "String", "ROWND_HUB_URL", '"https://<SUBDOMAIN>.rownd-hub.supertokens.com"'
        buildConfigField "String", "ROWND_DEEP_LINK_SCHEME", '"<APP_SCHEME_NAME>"'
    }
}
```

Use the values for your app:

- `ROWND_APP_KEY` - Your Rownd app key.
- `ROWND_API_DOMAIN` - The origin for your backend that exposes the SuperTokens APIs.
- `ROWND_API_BASE_PATH` - The SuperTokens API base path. This is usually `/auth`.
- `ROWND_HUB_URL` - The Rownd Hub URL for your app.
- `ROWND_DEEP_LINK_SCHEME` - The Android custom scheme your app registers and the SDK accepts.

There are two deep-link values to configure:

- Deep link scheme: the Android custom scheme your app registers and the SDK accepts, for example `rowndsupertokens`.
- Deep link: the verified HTTPS App Link on your custom Hub subdomain, for example `https://your-hub-subdomain.rownd-hub.supertokens.com/account/login`.

The Hub can derive the custom scheme fallback from your custom Hub subdomain, so make sure your Android custom scheme matches the scheme the Hub will generate.

### 2. Configure deep links

Add two intent filters: one for the custom scheme fallback and one for the verified HTTPS App Link. Keep `android:autoVerify="true"` only on the HTTPS filter.

```xml
<intent-filter>
    <action android:name="android.intent.action.VIEW" />

    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />

    <data android:scheme="${rowndDeepLinkScheme}" />
</intent-filter>

<intent-filter android:autoVerify="true">
    <action android:name="android.intent.action.VIEW" />

    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />

    <data
        android:scheme="https"
        android:host="<SUBDOMAIN>.rownd-hub.supertokens.com" />
</intent-filter>
```

The custom scheme handles fallback links such as `rowndsupertokens://account/login?...`. The HTTPS filter handles verified App Links for your custom Hub subdomain, such as `https://your-hub-subdomain.rownd-hub.supertokens.com/account/login?...`.

For verified App Links, the Hub domain's `assetlinks.json` must include your Android package name and signing certificate fingerprint. If you change the package name or signing key, update the asset links entry before relying on automatic app handoff.

Warm-start links are handled automatically when your activity extends `ComponentActivity`, including `FragmentActivity` and `AppCompatActivity`. A plain framework `Activity` must forward new intents:

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    Rownd.handleIntent(intent)
}
```

### 3. Initialize the Rownd SDK

The Rownd SDK needs access to your application's and current activity's context in order to properly manage state, display UI components, and so on.

The most straightforward way of doing this is to subclass the Android `Application` class and pass the app's primary context.

Configure Rownd in `Application.onCreate()`:

```kotlin
import android.app.Application
import io.rownd.android.Rownd
import io.rownd.android.RowndConfigureOptions

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        Rownd.config.enableDebugMode = BuildConfig.DEBUG
        Rownd.configure(
            this,
            RowndConfigureOptions(
                appKey = BuildConfig.ROWND_APP_KEY,
                apiDomain = BuildConfig.ROWND_API_DOMAIN,
                apiBasePath = BuildConfig.ROWND_API_BASE_PATH,
                deepLinkScheme = BuildConfig.ROWND_DEEP_LINK_SCHEME,
            )
        )
    }
}
```

Register your `Application` class in `AndroidManifest.xml`:

```xml
<application
    android:name=".MyApplication"
    ...>
</application>
```

`appVariantId` is optional. Set it in `RowndConfigureOptions` when this app belongs to a Rownd app variant so Hub and native Google sign-in include it in authentication requests.

`Rownd.configure(...)` automatically initializes the SuperTokens session integration once the Rownd app config has loaded. You do not need to manually initialize SuperTokens for the Rownd session bridge.

### 4. Request sign in

After initialization, call `Rownd.requestSignIn(...)` when the user should authenticate. This displays the Rownd interface and, after a successful sign-in, stores the Rownd auth state and bootstraps the SuperTokens session.

```kotlin
Rownd.requestSignIn(RowndSignInOptions())
```

You can also request a specific sign-in method when it is enabled for your Rownd app:

```kotlin
Rownd.requestSignIn(RowndSignInHint.OneTap)
Rownd.requestSignIn(RowndSignInHint.Guest)
Rownd.requestSignIn(RowndSignInHint.Apple)
```

### 5. Call protected APIs

Rownd manages the SuperTokens session automatically after sign-in. The SDK cannot attach SuperTokens headers to arbitrary HTTP clients your app creates, so protected backend calls still need a SuperTokens-aware client.

For `OkHttp`, add `SuperTokensInterceptor` to the client that calls protected APIs:

```kotlin
import com.supertokens.session.SuperTokensInterceptor
import okhttp3.OkHttpClient

val client = OkHttpClient.Builder()
    .addInterceptor(SuperTokensInterceptor())
    .build()
```

Use `Rownd.getAccessToken()` when you need the current access token directly:

```kotlin
val accessToken = Rownd.getAccessToken()
```

You can explicitly inspect or refresh the SuperTokens session through `SuperTokensSessionBridge` when needed for diagnostics or test flows:

```kotlin
val sessionExists = SuperTokensSessionBridge.doesSessionExist(applicationContext)
val refreshed = SuperTokensSessionBridge.attemptRefresh(applicationContext)
```

### Example app

See the sample app in this repository for a standalone Android app that follows these steps. From the repository root, run:

```bash
./gradlew :app:assembleLocalDebug
```

### Handling authentication

Rownd leverages an observable architecture to expose data to your app using [StateFlow](https://developer.android.com/kotlin/flow/stateflow-and-sharedflow). This means that as the Rownd state changes, an app can dynamically update without complicated logic. For example, a view can display different information based on the user's authentication status.

You can use this StateFlow in both older-style XML layouts as well as Android Jetpack's newer Composable views.

### Using state in XML layout

```xml
<!-- my_layout.xml -->

<?xml version="1.0" encoding="utf-8"?>
<layout xmlns:tools="http://schemas.android.com/tools"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:android="http://schemas.android.com/apk/res/android">

    <data>
        <import type="android.view.View" />
        <variable
            name="rownd"
            type="io.rownd.android.Rownd" />
    </data>

    <androidx.constraintlayout.widget.ConstraintLayout
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        tools:context=".MainActivity">

        <TextView
            android:id="@+id/textView"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@{@string/hello_rownd_state_user_data(rownd.state.user.data.first_name)}"
            app:layout_constraintBottom_toBottomOf="parent"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintTop_toTopOf="parent"
            app:layout_constraintVertical_bias="0.1.1099999" />

    </androidx.constraintlayout.widget.ConstraintLayout>
</layout>
```

```kotlin
// my_activity.kt
class MyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding: ActivityMainBinding =
            DataBindingUtil.setContentView(this, R.layout.activity_main)

        binding.lifecycleOwner = this

        binding.rownd = Rownd
    }
}

```

### Using state in a Composable

```kotlin
// some_activity_or_component.kt

class MyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val state = Rownd.state.collectAsState()
            val signInButtonText = if (state.value.auth.isAuthenticated) "Sign out" else "Sign in"
            Button(
                onClick = {
                    if (state.value.auth.isAuthenticated) Rownd.signOut()
                    else Rownd.requestSignIn()
                }
            ) {
                Text(signInButtonText)
            }
        }
    }
}
```

Once you subscribe to the Rownd state via `Rownd.state.collectAsState()`, you can use the various parts of the state tree as needed.

Access the state like this:

```kotlin
val rowndState = Rownd.state.collectAsState()

val isAuthenticated = rowndState.value.auth.isAuthenticated
```

The following classes/properties are available:

#### .auth

```kotlin
data class AuthState(
    val accessToken: String?, // Current, valid access token for the user (valid for one hour)
    val isVerifiedUser: Boolean, // Whether the current user has verified at least one identifier (e.g., email)
    val isAuthenticated: Boolean // Whether the current user is signed in
)
```

#### .user

```kotlin
data class User(
    val id: String?,  // The user's ID as known to Rownd
    val data: Map<String, @Serializable(with = AnyValueSerializer::class) Any?> = HashMap<String, Any?>(),
    val redacted: PersistentList<String> // A list of any profile fields that a user has restricted your app from accessing
)
```

## Customizing the UI

While most customizations are handled via the [Rownd dashboard](https://app.rownd.io), there are a few things that have to be customized directly in the SDK.

The `RowndCustomizations` class exists to facilitate these customizations. It provides the following properties that may be subclassed or overridden.

- `sheetBackgroundColor: Color?` (default: `null`) - Allows setting a single color for Rownd-provided bottom sheet interfaces regardless of system theme. Use this or `dynamicSheetBackgroundColor`, but not both.

- `dynamicSheetBackgroundColor: Color` (default: `light: #ffffff`, `dark: #1c1c1e`; requires subclassing) - Allows changing the background color underlying the bottom sheet that appears when signing in, managing the user account, etc. Based on the system color scheme.

- `sheetCornerBorderRadius: Dp` (default: `25.dp`) - Modifies the curvature radius of the bottom sheet's top corners.

- `loadingAnimation: Int` (default: null) - Replace Rownd's use of the system default loading spinner (i.e., `ProgressBar`) with a custom animation. Any animation resource compatible with [Lottie](https://airbnb.design/lottie/) should work, but will be scaled to fit a 1:1 aspect ratio (usually with a frame width/height of `100 Dp`) This should be a value like `R.raw.my_animation`

To apply customizations, we recommend subclassing the `RowndCustomizations` class. Here's an example:

```kotlin
class AppCustomizations(app: Application) : RowndCustomizations() {
    private var app: Application

    init {
        this.app = app
    }

    override val dynamicSheetBackgroundColor: Color
    get() {
            val uiMode = AppCompatDelegate.getDefaultNightMode()
            return if (uiMode == AppCompatDelegate.MODE_NIGHT_YES) {
                Color(0xff123456)
            } else {
                Color(0xfffedcba)

            }
        }

    override var sheetCornerBorderRadius: Dp = 25.dp

    override var loadingAnimation: Int? = R.raw.loading
}

// MyApplication.kt
import android.app.Application
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.rownd.android.Rownd
import io.rownd.android.RowndConfigureOptions
import io.rownd.android.models.RowndCustomizations

class MyApplication: Application() {
    override fun onCreate() {
        super.onCreate()

        Rownd.config.customizations = AppCustomizations(this)
        Rownd.configure(
            this,
            RowndConfigureOptions(
                appKey = BuildConfig.ROWND_APP_KEY,
                apiDomain = BuildConfig.ROWND_API_DOMAIN,
                apiBasePath = BuildConfig.ROWND_API_BASE_PATH,
                hubUrl = BuildConfig.ROWND_HUB_URL,
                deepLinkScheme = BuildConfig.ROWND_DEEP_LINK_SCHEME,
            )
        )
    }
}
```

## API reference

In addition to the StateFlow APIs, Rownd provides imperative APIs that you can call to request sign in, get and retrieve user profile information, retrieve a current access token, or encrypt user data with the user's local key.

### Rownd.requestSignIn(): Unit

Opens the Rownd sign-in dialog for authentication.

### Rownd.requestSignIn(RowndSignInHint): Unit

Initiates a sign-in using the specified hint, bypassing the authentication method selector. For example, this could be used to steer a new user toward a specific sign-in method.

Supported options:

- `RowndSignInHint.Google` - Prompt user to sign in with their Google account

- `RowndSignInHint.OneTap` - Prompt user to sign into their account with Google One Tap

- `RowndSignInHint.Guest` - Sign in the user anonymously as a guest.

- `RowndSignInHint.Apple` - Start Sign in with Apple through the Rownd Hub.

Example:

```kotlin
Rownd.requestSignIn(RowndSignInHint.Google)
```

### Rownd.requestSignIn(RowndSignInOptions(...)): Unit

Opens the Rownd sign-in dialog for authentication, as before, but allows passing additional context options as shown below.

- `intent: RowndSignInIntent` - This option applies only when you have opted to split the sign-up/sign-in flow via the Rownd dashboard. Valid values are `.SignIn` or `.SignUp`. If you don’t set this value, the user will be presented with the unified sign-in/sign-up flow. Please reach out to [support@rownd.io](mailto:support@rownd.io) to enable.

- `postSignInRedirect: String` (Not recommended) - The SDK and Hub normally handle redirects using your configured HTTPS App Link and custom scheme fallback. Use this only when you need to override the default redirect target. If you provide a custom value, it must still resolve back to your app through an Android App Link or a custom scheme your app handles.

Example:

```kotlin
Rownd.requestSignIn(RowndSignInOptions(
    intent = RowndSignInIntent.SignUp
))
```

### Rownd.signOut(): Void

Clears the user's access token, removes the user's profile data, and returns the user to a completely unauthenticated state.

### Rownd.signOut(scope = RowndSignOutScope): Void

Revokes all tokens for the specified user causing them to be signed out on all devices.

Supported options:

- `RowndSignOutScope.All`

  \- All devices

<br />

<Info>
  The following user profile APIs technically accept `Any` as the value of a field. However, that value **must** be serializable using [Kotlin's Serialization](https://kotlinlang.org/docs/serialization.html) library. If the value is not serializable out of the box, you'll need to provide your own serializer implementation as described in the Kotlin documentation.
</Info>

### suspend Rownd.getAccessToken(throwIfMissing: Boolean = false): String?

Assuming a user is signed-in, returns a valid access token, refreshing the current one if needed.

By default, this function will return `null` if an access token cannot be returned, either because the user is not signed in or because the refresh token is invalid.

If an access token cannot be returned due to a temporary condition (e.g., inaccessible network), this function will throw a `RowndException` indicating the failure reason (e.g., server or network error).

You may also set `throwIfMissing` to `true` to force an error to be thrown if an access token cannot be returned. This will provide more granular reasons for the failure. The possible error subtypes for `RowndException` in this case are:

- `NoAccessTokenPresentException(message: String)` - the user is not signed in
- `InvalidRefreshTokenException(message: String)` - the refresh token was invalid (e.g., the token was expired, revoked, or a previous exchange failed to complete successfully). The user will be signed out.
- `NetworkConnectionFailureException(details: String)` - a network condition prevented the token from being refreshed, even after several retries and should be re-attempted later. The user can remain signed-in.
- `ServerException(details: String)` - an error occurred on the server and you should try again later. The user can remain signed-in.

Example:

```kotlin
    try {
        val accessToken = Rownd.getAccessToken(throwIfMissing = true)
    } catch (e: RowndException) {
        when (e) {
          is NoAccessTokenPresentException -> { /* User is not signed in. do nothing. */ }
          is InvalidRefreshTokenException -> { /* Refresh token is invalid. User was signed out. Show splash page and error dialog */ }
          else -> { /* Something else went wrong. Ignore if possible, otherwise ask user to retry their action, connect to a network, close/reopen the app, etc. */ }
        }
    }
```

### Rownd.user.get(): Map\<String, Any?>

Returns the entire user profile as a Map

### Rownd.user.get\<T>(field: String): T?

Returns the value of a specific field in the user's data Map. `"id"` is a special case that will return the user's ID, even though it's technically not in the Map itself.

Your application code is responsible for knowing which type the value should cast to. If the cast fails or the entry doesn't exist, a `null` value will be returned.

### Rownd.user.set(data: Map\<String, Any?>): Void

Replaces the user's data with that contained in the Map. This may overwrite existing values, but must match the schema you defined within your Rownd application dashboard.

### Rownd.user.set(field: String, value: Any): Void

Sets a specific user profile field to the provided value, overwriting if a value already exists.

## Events

The Rownd SDK emits lifecycle events that you can listen to within your app. These events are primarily useful for detecting more granular aspects of a user's session (e.g., starting to sign in, completing sign-in, updated profile, etc.).

To listen to events, pass a function or closure that accepts a `RowndEvent` object to `Rownd.addEventListener()`. It might look something like this:

```kotlin
class MyApp: Application() {

    override fun onCreate() {
        super.onCreate()

        Rownd.addEventListener {
            when (it.event) {
                RowndEventType.SignInStarted -> {
                    // Do stuff
                }
                RowndEventType.SignInCompleted -> {
                    it.data?.get("user_type")?.let { it1 -> Log.d("App", it1.toString()) }
                }

                else -> {
                    // no-op
                }
            }
        }
    }
}
```

This registers the event listener with the Rownd SDK. You can also unregister the listener by calling `Rownd.removeEventListener()` with the same function or closure if you assign it to a variable.

Once the event handler is registered, it will receive events as they occur. The `RowndEvent` object contains the event type and any associated data. The event types are defined in the `RowndEventType` enum.

> NOTE: You'll need `implementation "org.jetbrains.kotlinx:kotlinx-serialization-json"` listed as a dependency in your `build.gradle` file in order to access the `data` `JsonObject` in the `RowndEvent` object.

#### List of events

Here's a list of events that the Rownd SDK emits and the corresponding data that should be present in the event data dictionary. Remember to write your code defensively, as the data dictionary may be missing keys in some cases.

<table>
  <tr>
    <th>Event</th>
    <th>Type</th>
    <th>Payload</th>
  </tr>

  <tr>
    <td>User started signing in</td>
    <td>RowndEventType.SignInStarted</td>

    <td>
      ```javascript
      {
      	method: "google" | "apple" | "phone" | "email" | "passkey" | etc
      }
      ```
    </td>

  </tr>

  <tr>
    <td>User signed in successfully</td>
    <td>RowndEventType.SignInCompleted</td>

    <td>
      ```javascript
      {
      	method: "google" | "apple" | "phone" | "email" | "passkey" | etc,
      	user_type: "new_user" | "existing_user"
          app_variant_user_type: 'new_user' | 'existing_user' | optional
      }
      ```
    </td>

  </tr>

  <tr>
    <td>User sign in failed</td>
    <td>RowndEventType.SignInFailed</td>

    <td>
      ```javascript
      {
      	reason: string
      }
      ```
    </td>

  </tr>
</table>

## Integration tests

The Android SDK integration tests use the local harness in `test-server/`. The harness starts SuperTokens Core and Postgres with Testcontainers, serves the Rownd plugin test API, and runs against an Android emulator.

Run integration tests:

```sh
npm run test:integration
```

Hub E2E tests require Node.js 22, Docker, a connected Android emulator, and read access to the private Hub repository. Prepare the pinned Hub revision next to this repository:

```sh
gh repo clone supertokens/supertokens-rownd-hub ../supertokens-rownd-hub
git -C ../supertokens-rownd-hub checkout 2146e7ad6f67473d7d5aadab2f94cc5373c5ff0b
npm ci --prefix ../supertokens-rownd-hub
```

Then install this repository's dependencies and run the suite:

```sh
npm ci
npm run test:e2e
```

The E2E suite builds and serves the local Hub, then verifies OTP and magic-link authentication through the Android WebView bridge, replay handling, restored-session account management, Hub profile-update persistence and native-state synchronization, and Hub-originated sign-out. Set `ANDROID_HUB_DIR` when the Hub checkout is not at `../supertokens-rownd-hub`.

The PR and release workflows run both E2E suites before merging or publishing.
Their local-Hub checkout uses `ROWND_HUB_REPOSITORY_TOKEN`, which must be a fine-grained token with read-only Contents access to `supertokens/supertokens-rownd-hub`. GitHub does not provide repository secrets to fork or Dependabot PRs, so the private-Hub matrix entry is skipped for those PRs.

Releases can also be started manually from GitHub Actions. E2E tests run by default; select `skip_tests` only when an explicit test bypass is required.

Run pending-email verification coverage on a connected emulator with Chrome installed:

```sh
npm run test:email-verification:e2e
```

This starts the harness, verifies Chrome custom-scheme dispatch to the local example app, then separately verifies native email verification and replacement-session adoption in the SDK. The two tests intentionally isolate OS handoff from SDK verification behavior.

Useful overrides:

- `ANDROID_HOST`: host address reachable from the Android device, default `10.0.2.2`
- `ANDROID_HUB_DIR`: local Hub checkout, default `../supertokens-rownd-hub`
- `ANDROID_HUB_PORT`: local Hub port, default `8787`
- `ANDROID_HARNESS_PORT`: local harness port, default `3138`
