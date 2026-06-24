package com.construct.messenger.domain.usecase

/**
 * Registration progress, reported by [RegisterUseCase] via its `onStep` callback.
 *
 * **Canon:** iOS `RegistrationFlowView.swift` → `enum RegistrationStep`. Kept structurally
 * identical (same stages, same PoW progress fraction) so the Android onboarding UI can
 * mirror `RegistrationStageView` 1:1 — see `OnboardingScreen.kt`.
 */
sealed interface RegistrationStep {
    data object GeneratingKeys : RegistrationStep
    data object FetchingChallenge : RegistrationStep
    data class ComputingPow(val progress: Float) : RegistrationStep
    data object SubmittingRegistration : RegistrationStep
    data object Complete : RegistrationStep
    data class Error(val message: String) : RegistrationStep
}
