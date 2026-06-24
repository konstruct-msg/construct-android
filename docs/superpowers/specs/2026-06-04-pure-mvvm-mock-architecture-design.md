# Pure MVVM Mock Architecture Design

Date: 2026-06-04
Branch: `main-mvvm-attempt`
Status: accepted for `main-mvvm-attempt` experiment

## Purpose

This branch tests a simple Android architecture that stays close to the iOS app's MVVM shape. The goal is to make the Android codebase easier to compare with iOS while the product is still using mock data and placeholder flows.

This spec is the architecture guide for future agents working on `main-mvvm-attempt`. Do not introduce a Clean Architecture/use-case layer in this branch unless the team explicitly changes direction and updates this document first.

## Current Context

The Android app is currently a Compose UI skeleton. The repo has design-system components, theme tokens, navigation, placeholder screens, Hilt setup, generated UniFFI bindings, and README stubs for future packages.

Implemented today:

- Compose-only UI with `MainActivity`, `KonstructNavHost`, and placeholder screens.
- CT design-system components under `ui/components/`.
- Theme tokens under `ui/theme/`.
- Hilt root via `KonstructApp`.
- Mock-like state inside Composables.

Not implemented today:

- Real `@HiltViewModel` classes.
- Repository interfaces and implementations.
- `CryptoManager` wrapper.
- Room, gRPC, session, registration, and message persistence flows.

## Architectural Decision

Use Pure MVVM for this branch:

```text
Compose Screen -> @HiltViewModel -> Repository interface -> Mock implementation
```

Screens render state and forward user actions. ViewModels own screen state, call repositories, and expose state to Compose. Repositories hide where data comes from. For now all repository implementations return mock data.

There is no domain/use-case layer in this attempt. If a future flow becomes too complex for a ViewModel plus repository, the team can revisit this decision after reviewing real pressure from the code.

## Package Layout

Use the repo's current documented structure:

```text
app/src/main/java/com/construct/messenger/
├── data/
│   ├── model/
│   │   ├── AuthState.kt
│   │   ├── ChatSummary.kt
│   │   └── Message.kt
│   ├── repository/
│   │   ├── AuthRepository.kt
│   │   ├── ChatsRepository.kt
│   │   └── SettingsRepository.kt
│   └── mock/
│       ├── MockAuthRepository.kt
│       ├── MockChatsRepository.kt
│       └── MockSettingsRepository.kt
├── di/
│   └── RepositoryModule.kt
├── viewmodel/
│   ├── SplashViewModel.kt
│   ├── OnboardingViewModel.kt
│   ├── MainViewModel.kt
│   └── SettingsViewModel.kt
└── ui/
    ├── navigation/
    ├── screens/
    ├── components/
    └── theme/
```

Keep ViewModels in the top-level `viewmodel/` package to mirror the iOS `ViewModels/` folder and match existing repo docs.

## Layer Rules

### UI Layer

Compose screens must be stateless or nearly stateless.

Allowed in screens:

- Collecting ViewModel state with lifecycle-aware collection.
- Rendering `UiState`.
- Calling ViewModel intent methods from click handlers.
- Triggering navigation from observed events.
- Local ephemeral UI state only when it is purely visual and disposable.

Not allowed in screens:

- Business decisions like "is the user registered?"
- Mock data construction.
- Calling repositories, UniFFI bindings, Room, gRPC, DataStore, or Keystore directly.
- Long-running work in `LaunchedEffect` unless it delegates to the ViewModel.

### ViewModel Layer

Every real screen gets an `@HiltViewModel`.

ViewModels should:

- Expose `StateFlow<ScreenUiState>`.
- Expose action methods such as `initializeIdentity()`, `refresh()`, or `openSettings()`.
- Depend on repository interfaces, not concrete mock classes.
- Convert repository results into UI-ready state.
- Own loading, empty, and error states.

ViewModels should not:

- Import Compose UI types.
- Know about `NavController`.
- Construct fake data inline.
- Call generated UniFFI bindings directly.

### Repository Layer

Repositories define app capabilities from the ViewModel's perspective.

For this branch:

- Repository-facing models live in `data/model/`.
- Repository interfaces live in `data/repository/`.
- Mock implementations live in `data/mock/`.
- Hilt binds interfaces to mock implementations.
- Mock implementations may use in-memory mutable state where useful.
- Mock repositories must be `@Singleton` unless they are explicitly stateless.

Future real implementations should replace mocks behind the same interfaces where possible.

### Dependency Injection

Use Hilt for ViewModels and repository bindings.

The default binding in this branch should point to mock implementations:

```text
AuthRepository -> MockAuthRepository
ChatsRepository -> MockChatsRepository
SettingsRepository -> MockSettingsRepository
```

Do not instantiate repositories manually inside ViewModels or Composables.

Stateful mock repositories should be application singletons so multiple ViewModels observe the same mock auth/session/chat state.

## State And Event Model

Each screen should have an explicit UI state model:

```kotlin
data class OnboardingUiState(
    val isInitializing: Boolean = false,
    val errorMessage: String? = null
)
```

Use `StateFlow` for durable screen state.

Use one-off events for navigation or transient effects:

```kotlin
sealed interface OnboardingEvent {
    data object NavigateToMain : OnboardingEvent
}
```

Events may be exposed with `SharedFlow`. Compose screens should collect one-off events from a `LaunchedEffect(viewModel)` or equivalent lifecycle-aware effect. One-off navigation should not be represented as durable `UiState`, because recomposition or configuration changes can replay durable state and trigger duplicate navigation.

Navigation should stay in the UI layer, but the decision that navigation is now allowed belongs to the ViewModel.

Lifecycle-aware state collection should use `collectAsStateWithLifecycle`. If the dependency is missing, add `androidx.lifecycle:lifecycle-runtime-compose` before wiring ViewModels into Compose screens.

## Mock Data Policy

Mock data is a first-class part of this architecture attempt.

Rules:

- Mock data lives outside UI.
- Repository-facing mock models live in `data/model/`; screen-specific UI state lives with ViewModels.
- Mock repositories should mimic expected real behavior, including loading and error paths when useful.
- Mock repositories should use meaningful sample models, not anonymous strings in Composables.
- Mock state should be easy to replace with Room/gRPC/Crypto-backed implementations later.

Initial mock scenarios:

- First launch: unauthenticated user routes from Splash to Onboarding.
- Identity initialization: Onboarding moves through idle/loading/success.
- Main screen: empty chat stream list.
- Future chat list: static conversations and messages from `MockChatsRepository`.

## Navigation Rules

Navigation is triggered by UI observing ViewModel events or state changes.

Rules:

- Do not navigate directly from repository callbacks.
- Do not put `NavController` in ViewModels.
- Replace hardcoded screen decisions such as `val isRegistered = false` with ViewModel state.
- Auth/onboarding to main navigation should clear the back stack so users cannot return to onboarding after initialization.
- Routes declared in `Screen.kt` should have matching `composable` entries or be removed until implemented.

## Error Handling

Even with mocks, ViewModels should model failures consistently:

- Loading state for work in progress.
- Empty state for valid but empty data.
- Error state for failed actions.
- Retry actions where the user can reasonably recover.

Visible strings must continue to use `strings.xml` through `stringResource` in the UI layer.

## Testing Strategy

Tests should focus on ViewModel behavior and repository contracts.

Recommended tests:

- `SplashViewModel` routes authenticated vs unauthenticated users correctly.
- `OnboardingViewModel` emits loading and success event when initialization succeeds.
- `OnboardingViewModel` exposes an error state when mock initialization fails.
- `MainViewModel` exposes an empty stream list from mock data.

Mock repositories make these tests cheap and do not require Android framework dependencies.

## Migration Path

This branch intentionally starts with mocks. Future real infrastructure should be added behind existing interfaces:

```text
MockAuthRepository -> RealAuthRepository
MockChatsRepository -> RealChatsRepository
MockSettingsRepository -> RealSettingsRepository
```

Expected future data sources:

- `CryptoManager` for identity and crypto operations.
- Room for local chats/messages/users.
- gRPC services for networking.
- DataStore for lightweight local settings and session flags.
- Android Keystore for sensitive local secrets.

If adding real data requires changing repository contracts, update this spec before changing the architecture.

## Strong Practice Review Findings

The current codebase already has good foundations: Compose-only UI, Hilt root setup, CT design-system components, localization scaffolding, and package placeholders.

The main current problems are architectural, not visual:

- Screens currently own placeholder business state.
- Splash routing is hardcoded.
- Onboarding has an `onInitialized` callback but does not call it.
- `Screen.Chat` and `Screen.Settings` exist without matching navigation destinations.
- Generated UniFFI bindings exist without the required `CryptoManager` facade.
- README/plan docs disagree in places about whether the app should use domain/use-case layers.

This MVVM attempt resolves the first three problems first and deliberately postpones real crypto, Room, and gRPC integration.

## Agent Instructions

Future agents working on `main-mvvm-attempt` must:

- Follow Pure MVVM unless this spec is updated.
- Keep ViewModels in top-level `viewmodel/`.
- Keep repository interfaces in `data/repository/`.
- Keep mock implementations in `data/mock/`.
- Avoid adding use-case classes in this branch.
- Avoid putting business state in Composables.
- Update this spec before changing architectural direction.

