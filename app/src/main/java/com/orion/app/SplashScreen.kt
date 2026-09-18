package com.orion.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.orion.R
import kotlinx.coroutines.delay

private const val REVEAL_DURATION_MS = 1400
private const val POST_REVEAL_DELAY_MS = 400L

/**
 * App launch splash. Pure UI: displays the Orion wordmark with a left-to-right
 * "typewriter" wipe reveal, then signals [onFinished] so the caller can move on
 * to the app's real routing (see MainActivity). No hardware, vendor, or
 * business logic lives here (CLAUDE.md §12).
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val revealProgress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        revealProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = REVEAL_DURATION_MS, easing = LinearEasing)
        )
        delay(POST_REVEAL_DELAY_MS)
        onFinished()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(R.drawable.orion_logo),
                contentDescription = "Orion",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .width(220.dp)
                    .drawWithContent {
                        clipRect(right = size.width * revealProgress.value) {
                            this@drawWithContent.drawContent()
                        }
                    }
            )
        }
    }
}
