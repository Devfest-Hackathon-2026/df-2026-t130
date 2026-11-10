// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui.component

import android.content.res.Resources
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.view.View
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.annotation.RawRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.viewinterop.AndroidView
import io.github.incallagent.rotep.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/** How AITEL looks on each screen. The words on the screen say the same; the character only adds to them. */
enum class AitelMood {
    /** 依頼 */
    HAPPY,

    /** 確認と承認：looking up, asking */
    ASKING,

    /** Waiting for the answer (ringing) */
    WAITING,

    /** Something is being started and will end by itself: a call being placed */
    GOING,

    /** The AI is talking */
    TALKING,

    /** The AI stopped and the user has the call */
    RESTING,

    /** Booked, and the check found nothing */
    GREAT,

    /** An answer that needs checking */
    WORRIED,

    /** Not reached, unavailable: nobody's fault */
    SAD,

    /** Something went wrong, or the call ended before the AI reported a result (someone hung up, or the user took over) */
    SORRY,
}

/** One of the owner's birds: the animation (WebP; null for a picture that does not move), and its first frame. */
private enum class Bird(@RawRes val moving: Int?, @DrawableRes val still: Int) {
    DANCE(R.raw.aitel_bird_dance, R.drawable.aitel_bird_dance_still),
    THINK(R.raw.aitel_bird_think, R.drawable.aitel_bird_think_still),
    WAIT(R.raw.aitel_bird_wait, R.drawable.aitel_bird_wait_still),
    WALK(R.raw.aitel_bird_walk, R.drawable.aitel_bird_walk_still),
    EXPLAIN(R.raw.aitel_bird_explain, R.drawable.aitel_bird_explain_still),
    REST(R.raw.aitel_bird_rest, R.drawable.aitel_bird_rest_still),
    SAD(R.raw.aitel_bird_sad, R.drawable.aitel_bird_sad_still),
    SORRY(null, R.drawable.aitel_bird_sorry_still),
}

private fun birdFor(mood: AitelMood): Bird = when (mood) {
    AitelMood.HAPPY, AitelMood.GREAT -> Bird.DANCE
    AitelMood.ASKING, AitelMood.WORRIED -> Bird.THINK
    AitelMood.WAITING -> Bird.WAIT
    AitelMood.GOING -> Bird.WALK
    AitelMood.TALKING -> Bird.EXPLAIN
    AitelMood.RESTING -> Bird.REST
    AitelMood.SAD -> Bird.SAD
    AitelMood.SORRY -> Bird.SORRY
}

/** null when it cannot be decoded: the first frame stays. A picture must not end a phone call. */
private fun decoded(resources: Resources, @RawRes animation: Int): AnimatedImageDrawable? = try {
    // From the bytes, not the resource: a resource source would enlarge every frame by the screen's density
    val bytes = resources.openRawResource(animation).use { it.readBytes() }
    ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) as? AnimatedImageDrawable
} catch (_: Exception) {
    null
} catch (_: OutOfMemoryError) {
    null
}

/**
 * AITEL, the owner's bird, in its [mood], moving and fitted to the size it is given. Until the animation
 * is decoded, in previews, and with the phone's animations removed it is the first frame, standing
 * still. Decorative: screen readers skip it.
 */
@Composable
fun AitelFace(mood: AitelMood, modifier: Modifier = Modifier) {
    val bird = birdFor(mood)
    val resources = LocalResources.current
    val preview = LocalInspectionMode.current
    val moving by produceState<AnimatedImageDrawable?>(null, bird) {
        value = null
        val animation = bird.moving
        if (animation == null || preview) return@produceState
        val scale = coroutineContext[MotionDurationScale]
        // The first read starts watching the phone's setting and writes state, which snapshotFlow's block cannot do
        scale?.scaleFactor
        snapshotFlow { scale?.scaleFactor == 0f }.collectLatest { off ->
            value = if (off) null else withContext(Dispatchers.Default) { decoded(resources, animation) }
        }
    }
    Box(modifier.clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        val drawable = moving
        if (drawable == null) {
            Image(painterResource(bird.still), contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            AndroidView(
                factory = { ImageView(it).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO } },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    if (view.drawable !== drawable) view.setImageDrawable(drawable)
                    drawable.start()
                },
                onRelease = { view -> (view.drawable as? AnimatedImageDrawable)?.stop() },
            )
        }
    }
}
