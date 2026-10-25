package app.apexview

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/** The browse navigation's shared-transition scope, so a card's artwork can become a page's. */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedScope = compositionLocalOf<SharedTransitionScope?> { null }

/** The animated scope of the page being shown, which shared elements animate within. */
val LocalPageScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

@OptIn(ExperimentalSharedTransitionApi::class)
private val ArtBounds = BoundsTransform { _, _ -> tween(Motion.SCREEN_MS, easing = EaseOut) }

/**
 * Marks artwork that should travel between pages: a card's image grows into the next page's
 * artwork, and shrinks back into the card on return. Both ends use the same [key]; the two
 * images cross-fade while the bounds move, since they're different crops and sizes.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedArt(key: String?, cornerRadius: Int = 0): Modifier {
    val shared = LocalSharedScope.current ?: return this
    val page = LocalPageScope.current ?: return this
    if (key == null) return this
    return with(shared) {
        this@sharedArt.sharedBounds(
            rememberSharedContentState(key),
            animatedVisibilityScope = page,
            enter = fadeIn(tween(Motion.SCREEN_MS, easing = EaseOut)),
            exit = fadeOut(tween(Motion.SCREEN_MS, easing = EaseOut)),
            boundsTransform = ArtBounds,
            resizeMode = SharedTransitionScope.ResizeMode.ScaleToBounds(ContentScale.Crop),
            clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(cornerRadius.dp)),
        )
    }
}
