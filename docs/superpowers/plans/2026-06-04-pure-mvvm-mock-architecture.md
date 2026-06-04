# Pure MVVM Mock Architecture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert the current mock Compose skeleton to the accepted Pure MVVM mock architecture on `main-mvvm-attempt`.

**Architecture:** Compose screens collect state from top-level `@HiltViewModel` classes. ViewModels depend on repository interfaces in `data/repository/`. Mock implementations in `data/mock/` are Hilt-bound singletons and own all fake app state.

**Tech Stack:** Kotlin, Jetpack Compose, Navigation Compose, Hilt, `StateFlow`, `SharedFlow`, `collectAsStateWithLifecycle`, JUnit, `kotlinx-coroutines-test`.

---

## Source Spec

Follow `docs/superpowers/specs/2026-06-04-pure-mvvm-mock-architecture-design.md`.

Do not introduce a domain/use-case layer in this implementation.

## File Structure

Create or modify these files:

- Modify: `app/build.gradle` - add lifecycle Compose and coroutine test dependencies.
- Create: `app/src/main/java/com/construct/messenger/data/model/AuthState.kt`
- Create: `app/src/main/java/com/construct/messenger/data/model/ChatSummary.kt`
- Create: `app/src/main/java/com/construct/messenger/data/model/Message.kt`
- Create: `app/src/main/java/com/construct/messenger/data/repository/AuthRepository.kt`
- Create: `app/src/main/java/com/construct/messenger/data/repository/ChatsRepository.kt`
- Create: `app/src/main/java/com/construct/messenger/data/mock/MockAuthRepository.kt`
- Create: `app/src/main/java/com/construct/messenger/data/mock/MockChatsRepository.kt`
- Create: `app/src/main/java/com/construct/messenger/di/RepositoryModule.kt`
- Create: `app/src/main/java/com/construct/messenger/viewmodel/SplashViewModel.kt`
- Create: `app/src/main/java/com/construct/messenger/viewmodel/OnboardingViewModel.kt`
- Create: `app/src/main/java/com/construct/messenger/viewmodel/MainViewModel.kt`
- Modify: `app/src/main/java/com/construct/messenger/ui/screens/splash/SplashScreen.kt`
- Modify: `app/src/main/java/com/construct/messenger/ui/screens/onboarding/OnboardingScreen.kt`
- Modify: `app/src/main/java/com/construct/messenger/ui/screens/main/MainScreen.kt`
- Modify: `app/src/main/java/com/construct/messenger/ui/navigation/NavHost.kt`
- Create: `app/src/main/java/com/construct/messenger/ui/screens/chat/ChatScreen.kt`
- Create: `app/src/main/java/com/construct/messenger/ui/screens/settings/SettingsScreen.kt`
- Create: `app/src/test/java/com/construct/messenger/test/MainDispatcherRule.kt`
- Create: `app/src/test/java/com/construct/messenger/data/mock/MockAuthRepositoryTest.kt`
- Create: `app/src/test/java/com/construct/messenger/viewmodel/SplashViewModelTest.kt`
- Create: `app/src/test/java/com/construct/messenger/viewmodel/OnboardingViewModelTest.kt`
- Create: `app/src/test/java/com/construct/messenger/viewmodel/MainViewModelTest.kt`

---

### Task 1: Add Required Dependencies

**Files:**
- Modify: `app/build.gradle`

- [ ] **Step 1: Add lifecycle Compose and coroutine test dependencies**

In `app/build.gradle`, add these lines in the existing `dependencies` block:

```groovy
implementation 'androidx.lifecycle:lifecycle-runtime-compose:2.6.1'
testImplementation 'org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0'
```

Keep the existing dependency style; do not convert the Gradle file to Kotlin DSL.

- [ ] **Step 2: Verify Gradle configuration**

Run:

```powershell
.\gradlew :app:dependencies --configuration debugRuntimeClasspath
```

Expected: command completes successfully and includes `androidx.lifecycle:lifecycle-runtime-compose`.

- [ ] **Step 3: Commit this bite if the user wants commits per task**

```powershell
git add app/build.gradle
git commit -m "chore: add mvvm support dependencies"
```

Skip the commit if the user asked not to commit.

---

### Task 2: Add Test Dispatcher Rule

**Files:**
- Create: `app/src/test/java/com/construct/messenger/test/MainDispatcherRule.kt`

- [ ] **Step 1: Create the test rule**

Create `MainDispatcherRule.kt` with this content:

```kotlin
package com.construct.messenger.test

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val testDispatcher: TestDispatcher = UnconfinedTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
```

- [ ] **Step 2: Run unit tests**

Run:

```powershell
.\gradlew test
```

Expected: existing tests pass.

- [ ] **Step 3: Commit this bite if the user wants commits per task**

```powershell
git add app/src/test/java/com/construct/messenger/test/MainDispatcherRule.kt
git commit -m "test: add main dispatcher rule"
```

Skip the commit if the user asked not to commit.

---

### Task 3: Add Auth Repository Contract And Mock

**Files:**
- Create: `app/src/main/java/com/construct/messenger/data/model/AuthState.kt`
- Create: `app/src/main/java/com/construct/messenger/data/repository/AuthRepository.kt`
- Create: `app/src/main/java/com/construct/messenger/data/mock/MockAuthRepository.kt`
- Create: `app/src/test/java/com/construct/messenger/data/mock/MockAuthRepositoryTest.kt`

- [ ] **Step 1: Write the failing repository test**

Create `MockAuthRepositoryTest.kt`:

```kotlin
package com.construct.messenger.data.mock

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MockAuthRepositoryTest {
    @Test
    fun startsUnauthenticatedAndCanInitializeIdentity() = runTest {
        val repository = MockAuthRepository()

        assertFalse(repository.authState.value.isInitialized)

        repository.initializeIdentity()

        assertTrue(repository.authState.value.isInitialized)
    }
}
```

- [ ] **Step 2: Run the failing test**

Run:

```powershell
.\gradlew :app:testDebugUnitTest --tests "com.construct.messenger.data.mock.MockAuthRepositoryTest"
```

Expected: fails because `MockAuthRepository` does not exist.

- [ ] **Step 3: Add auth state model**

Create `AuthState.kt`:

```kotlin
package com.construct.messenger.data.model

data class AuthState(
    val isInitialized: Boolean = false
)
```

- [ ] **Step 4: Add repository interface**

Create `AuthRepository.kt`:

```kotlin
package com.construct.messenger.data.repository

import com.construct.messenger.data.model.AuthState
import kotlinx.coroutines.flow.StateFlow

interface AuthRepository {
    val authState: StateFlow<AuthState>

    suspend fun initializeIdentity()
}
```

- [ ] **Step 5: Add mock implementation**

Create `MockAuthRepository.kt`:

```kotlin
package com.construct.messenger.data.mock

import com.construct.messenger.data.model.AuthState
import com.construct.messenger.data.repository.AuthRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MockAuthRepository @Inject constructor() : AuthRepository {
    private val mutableAuthState = MutableStateFlow(AuthState())

    override val authState: StateFlow<AuthState> = mutableAuthState.asStateFlow()

    override suspend fun initializeIdentity() {
        delay(250)
        mutableAuthState.value = AuthState(isInitialized = true)
    }
}
```

- [ ] **Step 6: Run the test**

Run:

```powershell
.\gradlew :app:testDebugUnitTest --tests "com.construct.messenger.data.mock.MockAuthRepositoryTest"
```

Expected: PASS.

- [ ] **Step 7: Commit this bite if the user wants commits per task**

```powershell
git add app/src/main/java/com/construct/messenger/data/model/AuthState.kt app/src/main/java/com/construct/messenger/data/repository/AuthRepository.kt app/src/main/java/com/construct/messenger/data/mock/MockAuthRepository.kt app/src/test/java/com/construct/messenger/data/mock/MockAuthRepositoryTest.kt
git commit -m "feat: add mock auth repository"
```

Skip the commit if the user asked not to commit.

---

### Task 4: Bind Mock Repositories With Hilt

**Files:**
- Create: `app/src/main/java/com/construct/messenger/di/RepositoryModule.kt`

- [ ] **Step 1: Add Hilt module**

Create `RepositoryModule.kt`:

```kotlin
package com.construct.messenger.di

import com.construct.messenger.data.mock.MockAuthRepository
import com.construct.messenger.data.repository.AuthRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindAuthRepository(
        repository: MockAuthRepository
    ): AuthRepository
}
```

- [ ] **Step 2: Compile**

Run:

```powershell
.\gradlew :app:compileDebugKotlin
```

Expected: compile succeeds.

- [ ] **Step 3: Commit this bite if the user wants commits per task**

```powershell
git add app/src/main/java/com/construct/messenger/di/RepositoryModule.kt
git commit -m "feat: bind mock repositories"
```

Skip the commit if the user asked not to commit.

---

### Task 5: Add SplashViewModel

**Files:**
- Create: `app/src/main/java/com/construct/messenger/viewmodel/SplashViewModel.kt`
- Create: `app/src/test/java/com/construct/messenger/viewmodel/SplashViewModelTest.kt`

- [ ] **Step 1: Write failing ViewModel tests**

Create `SplashViewModelTest.kt`:

```kotlin
package com.construct.messenger.viewmodel

import com.construct.messenger.data.mock.MockAuthRepository
import com.construct.messenger.test.MainDispatcherRule
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SplashViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun unauthenticatedUserRoutesToOnboarding() = runTest {
        val repository = MockAuthRepository()
        val viewModel = SplashViewModel(repository)

        viewModel.decideNextRoute()
        advanceUntilIdle()

        assertEquals(SplashRoute.Onboarding, viewModel.uiState.value.route)
    }

    @Test
    fun initializedUserRoutesToMain() = runTest {
        val repository = MockAuthRepository()
        repository.initializeIdentity()
        val viewModel = SplashViewModel(repository)

        viewModel.decideNextRoute()
        advanceUntilIdle()

        assertEquals(SplashRoute.Main, viewModel.uiState.value.route)
    }
}
```

- [ ] **Step 2: Run the failing tests**

Run:

```powershell
.\gradlew :app:testDebugUnitTest --tests "com.construct.messenger.viewmodel.SplashViewModelTest"
```

Expected: fails because `SplashViewModel` does not exist.

- [ ] **Step 3: Add SplashViewModel**

Create `SplashViewModel.kt`:

```kotlin
package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SplashUiState(
    val route: SplashRoute? = null
)

enum class SplashRoute {
    Onboarding,
    Main
}

@HiltViewModel
class SplashViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(SplashUiState())

    val uiState: StateFlow<SplashUiState> = mutableUiState.asStateFlow()

    fun decideNextRoute() {
        viewModelScope.launch {
            val route = if (authRepository.authState.value.isInitialized) {
                SplashRoute.Main
            } else {
                SplashRoute.Onboarding
            }
            mutableUiState.update { it.copy(route = route) }
        }
    }
}
```

- [ ] **Step 4: Run tests**

Run:

```powershell
.\gradlew :app:testDebugUnitTest --tests "com.construct.messenger.viewmodel.SplashViewModelTest"
```

Expected: PASS.

This task intentionally keeps the splash routing decision as durable `SplashUiState.route` because splash routing is a terminal startup decision, not a reusable user action. Do not copy this pattern for user-triggered one-off navigation events; use `SharedFlow` as shown in `OnboardingViewModel`.

- [ ] **Step 5: Commit this bite if the user wants commits per task**

```powershell
git add app/src/main/java/com/construct/messenger/viewmodel/SplashViewModel.kt app/src/test/java/com/construct/messenger/viewmodel/SplashViewModelTest.kt
git commit -m "feat: add splash view model"
```

Skip the commit if the user asked not to commit.

---

### Task 6: Wire SplashScreen To SplashViewModel

**Files:**
- Modify: `app/src/main/java/com/construct/messenger/ui/screens/splash/SplashScreen.kt`
- Modify: `app/src/main/java/com/construct/messenger/ui/navigation/NavHost.kt`

- [ ] **Step 1: Replace SplashScreen with ViewModel-backed implementation**

Replace `SplashScreen.kt` with:

```kotlin
package com.construct.messenger.ui.screens.splash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.ctBold
import com.construct.messenger.viewmodel.SplashRoute
import com.construct.messenger.viewmodel.SplashViewModel

@Composable
fun SplashScreen(
    onNavigateToOnboarding: () -> Unit,
    onNavigateToMain: () -> Unit,
    viewModel: SplashViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.decideNextRoute()
    }

    LaunchedEffect(uiState.route) {
        when (uiState.route) {
            SplashRoute.Onboarding -> onNavigateToOnboarding()
            SplashRoute.Main -> onNavigateToMain()
            null -> Unit
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.brand_name),
            style = ctBold(24),
            color = CTColor.text,
            letterSpacing = 8.sp
        )
    }
}
```

- [ ] **Step 2: Update navigation to clear splash from the back stack**

In `NavHost.kt`, replace the Splash destination lambdas with:

```kotlin
SplashScreen(
    onNavigateToOnboarding = {
        navController.navigate(Screen.Onboarding.route) {
            popUpTo(Screen.Splash.route) { inclusive = true }
            launchSingleTop = true
        }
    },
    onNavigateToMain = {
        navController.navigate(Screen.Main.route) {
            popUpTo(Screen.Splash.route) { inclusive = true }
            launchSingleTop = true
        }
    }
)
```

- [ ] **Step 3: Compile**

Run:

```powershell
.\gradlew :app:compileDebugKotlin
```

Expected: compile succeeds.

- [ ] **Step 4: Commit this bite if the user wants commits per task**

```powershell
git add app/src/main/java/com/construct/messenger/ui/screens/splash/SplashScreen.kt app/src/main/java/com/construct/messenger/ui/navigation/NavHost.kt
git commit -m "feat: wire splash to view model"
```

Skip the commit if the user asked not to commit.

---

### Task 7: Add OnboardingViewModel

**Files:**
- Create: `app/src/main/java/com/construct/messenger/viewmodel/OnboardingViewModel.kt`
- Create: `app/src/test/java/com/construct/messenger/viewmodel/OnboardingViewModelTest.kt`

- [ ] **Step 1: Write failing ViewModel test**

Create `OnboardingViewModelTest.kt`:

```kotlin
package com.construct.messenger.viewmodel

import com.construct.messenger.data.mock.MockAuthRepository
import com.construct.messenger.test.MainDispatcherRule
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class OnboardingViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun initializeIdentityEmitsNavigateToMain() = runTest {
        val viewModel = OnboardingViewModel(MockAuthRepository())
        val event = async { viewModel.events.first() }

        viewModel.initializeIdentity()
        advanceUntilIdle()

        assertEquals(OnboardingEvent.NavigateToMain, event.await())
        assertFalse(viewModel.uiState.value.isInitializing)
    }
}
```

- [ ] **Step 2: Run the failing test**

Run:

```powershell
.\gradlew :app:testDebugUnitTest --tests "com.construct.messenger.viewmodel.OnboardingViewModelTest"
```

Expected: fails because `OnboardingViewModel` does not exist.

- [ ] **Step 3: Add OnboardingViewModel**

Create `OnboardingViewModel.kt`:

```kotlin
package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OnboardingUiState(
    val isInitializing: Boolean = false,
    val errorMessage: String? = null
)

sealed interface OnboardingEvent {
    data object NavigateToMain : OnboardingEvent
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(OnboardingUiState())
    private val mutableEvents = MutableSharedFlow<OnboardingEvent>()

    val uiState: StateFlow<OnboardingUiState> = mutableUiState.asStateFlow()
    val events: SharedFlow<OnboardingEvent> = mutableEvents.asSharedFlow()

    fun initializeIdentity() {
        viewModelScope.launch {
            mutableUiState.update {
                it.copy(isInitializing = true, errorMessage = null)
            }

            runCatching {
                authRepository.initializeIdentity()
            }.onSuccess {
                mutableUiState.update { it.copy(isInitializing = false) }
                mutableEvents.emit(OnboardingEvent.NavigateToMain)
            }.onFailure { error ->
                mutableUiState.update {
                    it.copy(
                        isInitializing = false,
                        errorMessage = error.message ?: "Unable to initialize identity"
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 4: Run tests**

Run:

```powershell
.\gradlew :app:testDebugUnitTest --tests "com.construct.messenger.viewmodel.OnboardingViewModelTest"
```

Expected: PASS.

- [ ] **Step 5: Commit this bite if the user wants commits per task**

```powershell
git add app/src/main/java/com/construct/messenger/viewmodel/OnboardingViewModel.kt app/src/test/java/com/construct/messenger/viewmodel/OnboardingViewModelTest.kt
git commit -m "feat: add onboarding view model"
```

Skip the commit if the user asked not to commit.

---

### Task 8: Wire OnboardingScreen To OnboardingViewModel

**Files:**
- Modify: `app/src/main/java/com/construct/messenger/ui/screens/onboarding/OnboardingScreen.kt`
- Modify: `app/src/main/java/com/construct/messenger/ui/navigation/NavHost.kt`

- [ ] **Step 1: Replace OnboardingScreen local state with ViewModel state**

Replace `OnboardingScreen.kt` with:

```kotlin
package com.construct.messenger.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTSymbol
import com.construct.messenger.ui.theme.ctBold
import com.construct.messenger.ui.theme.ctRegular
import com.construct.messenger.viewmodel.OnboardingEvent
import com.construct.messenger.viewmodel.OnboardingViewModel

@Composable
fun OnboardingScreen(
    onInitialized: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                OnboardingEvent.NavigateToMain -> onInitialized()
            }
        }
    }

    Box(
        modifier = Modifier
            .padding(top = 20.dp)
            .fillMaxSize()
            .background(CTColor.bg)
            .padding(20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = stringResource(R.string.brand_name),
                    style = ctBold(18),
                    color = CTColor.text,
                    letterSpacing = 6.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.onboarding_subtitle),
                    style = ctBold(14),
                    color = CTColor.textDim,
                    letterSpacing = 2.sp
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        enabled = !uiState.isInitializing,
                        onClick = viewModel::initializeIdentity
                    )
                    .background(CTColor.bgMsg)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (uiState.isInitializing) {
                        CTSymbol.loading
                    } else {
                        stringResource(R.string.onboarding_init_action)
                    },
                    style = ctBold(14),
                    color = CTColor.accent,
                    letterSpacing = 2.sp
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.onboarding_security_title),
                    style = ctBold(10),
                    color = CTColor.textDim,
                    letterSpacing = 2.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.onboarding_security_body),
                    style = ctRegular(12),
                    color = CTColor.textDim,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}
```

- [ ] **Step 2: Update onboarding navigation to clear onboarding from the stack**

In `NavHost.kt`, replace the Onboarding destination callback with:

```kotlin
OnboardingScreen(
    onInitialized = {
        navController.navigate(Screen.Main.route) {
            popUpTo(Screen.Onboarding.route) { inclusive = true }
            launchSingleTop = true
        }
    }
)
```

- [ ] **Step 3: Compile**

Run:

```powershell
.\gradlew :app:compileDebugKotlin
```

Expected: compile succeeds.

- [ ] **Step 4: Commit this bite if the user wants commits per task**

```powershell
git add app/src/main/java/com/construct/messenger/ui/screens/onboarding/OnboardingScreen.kt app/src/main/java/com/construct/messenger/ui/navigation/NavHost.kt
git commit -m "feat: wire onboarding to view model"
```

Skip the commit if the user asked not to commit.

---

### Task 9: Add Chats Repository And MainViewModel

**Files:**
- Create: `app/src/main/java/com/construct/messenger/data/model/ChatSummary.kt`
- Create: `app/src/main/java/com/construct/messenger/data/model/Message.kt`
- Create: `app/src/main/java/com/construct/messenger/data/repository/ChatsRepository.kt`
- Create: `app/src/main/java/com/construct/messenger/data/mock/MockChatsRepository.kt`
- Modify: `app/src/main/java/com/construct/messenger/di/RepositoryModule.kt`
- Create: `app/src/main/java/com/construct/messenger/viewmodel/MainViewModel.kt`
- Create: `app/src/test/java/com/construct/messenger/viewmodel/MainViewModelTest.kt`

- [ ] **Step 1: Write failing MainViewModel test**

Create `MainViewModelTest.kt`:

```kotlin
package com.construct.messenger.viewmodel

import com.construct.messenger.data.mock.MockChatsRepository
import com.construct.messenger.test.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MainViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun exposesEmptyMockStreamList() = runTest {
        val viewModel = MainViewModel(MockChatsRepository())

        assertTrue(viewModel.uiState.value.chats.isEmpty())
        assertEquals("test_contact", viewModel.uiState.value.suggestedContactId)
    }
}
```

- [ ] **Step 2: Run the failing test**

Run:

```powershell
.\gradlew :app:testDebugUnitTest --tests "com.construct.messenger.viewmodel.MainViewModelTest"
```

Expected: fails because `MainViewModel` does not exist.

- [ ] **Step 3: Add chat models**

Create `ChatSummary.kt`:

```kotlin
package com.construct.messenger.data.model

data class ChatSummary(
    val contactId: String,
    val displayName: String,
    val lastMessagePreview: String?
)
```

Create `Message.kt`:

```kotlin
package com.construct.messenger.data.model

data class Message(
    val id: String,
    val chatId: String,
    val body: String,
    val isOutgoing: Boolean
)
```

- [ ] **Step 4: Add chats repository interface**

Create `ChatsRepository.kt`:

```kotlin
package com.construct.messenger.data.repository

import com.construct.messenger.data.model.ChatSummary
import kotlinx.coroutines.flow.StateFlow

interface ChatsRepository {
    val chats: StateFlow<List<ChatSummary>>

    fun suggestedContactId(): String
}
```

- [ ] **Step 5: Add mock chats repository**

Create `MockChatsRepository.kt`:

```kotlin
package com.construct.messenger.data.mock

import com.construct.messenger.data.model.ChatSummary
import com.construct.messenger.data.repository.ChatsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MockChatsRepository @Inject constructor() : ChatsRepository {
    private val mutableChats = MutableStateFlow<List<ChatSummary>>(emptyList())

    override val chats: StateFlow<List<ChatSummary>> = mutableChats.asStateFlow()

    override fun suggestedContactId(): String = "test_contact"
}
```

- [ ] **Step 6: Extend RepositoryModule**

Replace `RepositoryModule.kt` with:

```kotlin
package com.construct.messenger.di

import com.construct.messenger.data.mock.MockAuthRepository
import com.construct.messenger.data.mock.MockChatsRepository
import com.construct.messenger.data.repository.AuthRepository
import com.construct.messenger.data.repository.ChatsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindAuthRepository(
        repository: MockAuthRepository
    ): AuthRepository

    @Binds
    @Singleton
    abstract fun bindChatsRepository(
        repository: MockChatsRepository
    ): ChatsRepository
}
```

- [ ] **Step 7: Add MainViewModel**

Create `MainViewModel.kt`:

```kotlin
package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import com.construct.messenger.data.model.ChatSummary
import com.construct.messenger.data.repository.ChatsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import javax.inject.Inject

data class MainUiState(
    val chats: List<ChatSummary> = emptyList(),
    val suggestedContactId: String = ""
)

@HiltViewModel
class MainViewModel @Inject constructor(
    chatsRepository: ChatsRepository
) : ViewModel() {
    val uiState: StateFlow<MainUiState> = chatsRepository.chats
        .map { chats ->
            MainUiState(
                chats = chats,
                suggestedContactId = chatsRepository.suggestedContactId()
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MainUiState(
                chats = chatsRepository.chats.value,
                suggestedContactId = chatsRepository.suggestedContactId()
            )
        )
}
```

- [ ] **Step 8: Run tests**

Run:

```powershell
.\gradlew :app:testDebugUnitTest --tests "com.construct.messenger.viewmodel.MainViewModelTest"
```

Expected: PASS.

- [ ] **Step 9: Commit this bite if the user wants commits per task**

```powershell
git add app/src/main/java/com/construct/messenger/data/model/ChatSummary.kt app/src/main/java/com/construct/messenger/data/model/Message.kt app/src/main/java/com/construct/messenger/data/repository/ChatsRepository.kt app/src/main/java/com/construct/messenger/data/mock/MockChatsRepository.kt app/src/main/java/com/construct/messenger/di/RepositoryModule.kt app/src/main/java/com/construct/messenger/viewmodel/MainViewModel.kt app/src/test/java/com/construct/messenger/viewmodel/MainViewModelTest.kt
git commit -m "feat: add mock chats view model"
```

Skip the commit if the user asked not to commit.

---

### Task 10: Wire MainScreen To MainViewModel

**Files:**
- Modify: `app/src/main/java/com/construct/messenger/ui/screens/main/MainScreen.kt`

- [ ] **Step 1: Replace hardcoded contact id with ViewModel state**

Replace `MainScreen.kt` with:

```kotlin
package com.construct.messenger.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.ui.components.CTNavBar
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTSymbol
import com.construct.messenger.ui.theme.ctBold
import com.construct.messenger.ui.theme.ctRegular
import com.construct.messenger.viewmodel.MainViewModel

@Composable
fun MainScreen(
    onNavigateToChat: (String) -> Unit,
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            CTNavBar(
                title = stringResource(R.string.nav_streams),
                trailingIcon = Icons.Default.Settings,
                trailingSecondaryIcon = Icons.Default.Search,
                onTrailingAction = {},
                onTrailingSecondaryAction = {},
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            ) {
                Text(
                    text = stringResource(R.string.main_empty_title),
                    style = ctRegular(14),
                    color = CTColor.textDim
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.main_empty_subtitle),
                    style = ctRegular(12),
                    color = CTColor.textDim,
                    letterSpacing = 1.sp
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToChat(uiState.suggestedContactId) }
                    .background(CTColor.bgMsg)
                    .padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = CTSymbol.add,
                        style = ctBold(14),
                        color = CTColor.accent
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.main_start_stream),
                        style = ctRegular(14),
                        color = CTColor.text
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 2: Compile**

Run:

```powershell
.\gradlew :app:compileDebugKotlin
```

Expected: compile succeeds.

- [ ] **Step 3: Commit this bite if the user wants commits per task**

```powershell
git add app/src/main/java/com/construct/messenger/ui/screens/main/MainScreen.kt
git commit -m "feat: wire main screen to view model"
```

Skip the commit if the user asked not to commit.

---

### Task 11: Resolve Navigation Route Consistency

**Files:**
- Modify: `app/src/main/java/com/construct/messenger/ui/navigation/Screen.kt`
- Modify: `app/src/main/java/com/construct/messenger/ui/navigation/NavHost.kt`
- Create: `app/src/main/java/com/construct/messenger/ui/screens/chat/ChatScreen.kt`
- Create: `app/src/main/java/com/construct/messenger/ui/screens/settings/SettingsScreen.kt`

- [ ] **Step 1: Create a minimal chat destination to match `Screen.Chat`**

Create `ChatScreen.kt`:

```kotlin
package com.construct.messenger.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.ctRegular

@Composable
fun ChatScreen(
    contactId: String
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = contactId,
            style = ctRegular(14),
            color = CTColor.textDim
        )
    }
}
```

- [ ] **Step 2: Create a minimal settings destination to match `Screen.Settings`**

Create `SettingsScreen.kt`:

```kotlin
package com.construct.messenger.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.construct.messenger.R
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.ctRegular

@Composable
fun SettingsScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = ctRegular(14),
            color = CTColor.textDim
        )
    }
}
```

- [ ] **Step 3: Register Chat and Settings routes in NavHost**

In `NavHost.kt`, add:

```kotlin
import com.construct.messenger.ui.screens.chat.ChatScreen
import com.construct.messenger.ui.screens.settings.SettingsScreen
```

Then add these destinations inside `NavHost`:

```kotlin
composable(
    route = Screen.Chat.route,
    arguments = listOf(navArgument("contactId") { type = NavType.StringType })
) { backStackEntry ->
    ChatScreen(
        contactId = backStackEntry.arguments?.getString("contactId").orEmpty()
    )
}

composable(Screen.Settings.route) {
    SettingsScreen()
}
```

- [ ] **Step 4: Make chat navigation single-top**

In the existing `MainScreen` destination, update the chat navigation callback to avoid stacking duplicate chat destinations on rapid taps:

```kotlin
MainScreen(
    onNavigateToChat = { contactId ->
        navController.navigate(Screen.Chat.createRoute(contactId)) {
            launchSingleTop = true
        }
    }
)
```

- [ ] **Step 5: Encode chat route arguments and fail fast on missing args**

In `Screen.kt`, encode the contact id before placing it in the route path segment:

```kotlin
import android.net.Uri

// ...
data object Chat : Screen("chat/{contactId}") {
    fun createRoute(contactId: String) = "chat/${Uri.encode(contactId)}"
}
```

In `NavHost.kt`, fail fast if the required argument is missing:

```kotlin
ChatScreen(
    contactId = requireNotNull(backStackEntry.arguments?.getString("contactId")) {
        "Missing contactId route argument"
    }
)
```

- [ ] **Step 6: Compile**

Run:

```powershell
.\gradlew :app:compileDebugKotlin
```

Expected: compile succeeds.

- [ ] **Step 7: Commit this bite if the user wants commits per task**

```powershell
git add app/src/main/java/com/construct/messenger/ui/navigation/Screen.kt app/src/main/java/com/construct/messenger/ui/navigation/NavHost.kt app/src/main/java/com/construct/messenger/ui/screens/chat/ChatScreen.kt app/src/main/java/com/construct/messenger/ui/screens/settings/SettingsScreen.kt
git commit -m "feat: add mock navigation routes"
```

Skip the commit if the user asked not to commit.

---

### Task 12: Final Verification

**Files:**
- No code changes expected.

- [ ] **Step 1: Run focused unit tests**

Run:

```powershell
.\gradlew :app:testDebugUnitTest --tests "com.construct.messenger.data.mock.MockAuthRepositoryTest" --tests "com.construct.messenger.viewmodel.*"
```

Expected: repository and ViewModel tests pass.

- [ ] **Step 2: Run full unit test suite**

Run:

```powershell
.\gradlew test
```

Expected: all JVM tests pass.

- [ ] **Step 3: Compile debug Kotlin**

Run:

```powershell
.\gradlew :app:compileDebugKotlin
```

Expected: compile succeeds.

- [ ] **Step 4: Check changed files**

Run:

```powershell
git status --short
```

Expected: only files from this MVVM work, the spec, and the plan are listed, plus any pre-existing user changes.

- [ ] **Step 5: Commit final verification cleanup if the user wants commits per task**

If any small compile/test cleanup happened during verification:

```powershell
git add app docs/superpowers
git commit -m "test: verify pure mvvm mock flow"
```

Skip the commit if the user asked not to commit.

---

## Self-Review

Spec coverage:

- Pure MVVM is implemented through `Screen -> ViewModel -> Repository -> Mock`.
- ViewModels are top-level under `viewmodel/`.
- Repository interfaces are under `data/repository/`.
- Mock implementations are under `data/mock/`.
- Repository-facing models are under `data/model/`.
- No use-case layer is introduced.
- Compose screens stop owning business state.
- Mock repositories are singleton Hilt bindings.
- Navigation state is owned by ViewModels and executed by UI callbacks.
- `collectAsStateWithLifecycle` dependency is added before screen wiring.
- Declared `Screen.Chat` and `Screen.Settings` routes receive matching destinations.

Placeholder scan:

- This plan avoids unresolved placeholders and keeps future work outside this pass.

Type consistency:

- `AuthRepository`, `MockAuthRepository`, `SplashViewModel`, `OnboardingViewModel`, and `MainViewModel` signatures match across tests and implementation snippets.
- `SplashRoute`, `OnboardingEvent`, and UI state names are consistent across code snippets.

