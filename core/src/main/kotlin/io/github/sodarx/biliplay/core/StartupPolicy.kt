package io.github.sodarx.biliplay.core
enum class StartupStep {
    Agreement,
    Appearance,
    Login,
    Ready,
}

object StartupPolicy {
    fun step(agreement: Boolean, appearance: Boolean, authenticated: Boolean) =
        when {
            !agreement -> StartupStep.Agreement
            !appearance -> StartupStep.Appearance
            !authenticated -> StartupStep.Login
            else -> StartupStep.Ready
        }
}
