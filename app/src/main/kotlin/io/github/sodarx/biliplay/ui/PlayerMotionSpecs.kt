package io.github.sodarx.biliplay.ui

import androidx.compose.animation.core.*

/** The same normalized spring drives expansion, dismissal, artwork and dock restoration. */
internal fun <T> playerSpatialSpec(): SpringSpec<T> = spring(dampingRatio = .8f, stiffness = 420f)
