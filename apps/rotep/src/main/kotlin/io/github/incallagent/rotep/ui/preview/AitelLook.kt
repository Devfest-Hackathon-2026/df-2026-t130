// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui.preview

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.incallagent.rotep.R
import io.github.incallagent.rotep.ui.component.AitelCallHeader
import io.github.incallagent.rotep.ui.component.AitelFace
import io.github.incallagent.rotep.ui.component.AitelHero
import io.github.incallagent.rotep.ui.component.AitelMood
import io.github.incallagent.rotep.ui.component.AitelTitle
import io.github.incallagent.rotep.ui.component.HeroKind
import io.github.incallagent.rotep.ui.theme.RotepTheme

/** AITEL's look in one picture: the title, the four heroes and the ten moods (for choosing the design). */
@Composable
private fun Look(dark: Boolean) {
    RotepTheme(darkTheme = dark) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AitelTitle(Modifier.weight(1f))
                    TextButton(onClick = {}) { Text("設定") }
                }
                AitelHero(HeroKind.REQUEST, "依頼", "どこに", "電話する？", AitelMood.HAPPY)
                AitelHero(HeroKind.CONFIRM, "確認と承認", "レストラン・サンプルに", "電話していい？", AitelMood.ASKING)
                AitelCallHeader("通話中", "AI が会話しています", AitelMood.TALKING)
                AitelHero(HeroKind.RESULT_GOOD, "結果", "予約", "できました！", AitelMood.GREAT)
                AitelHero(HeroKind.RESULT_OTHER, "結果", "確認が", "要ります", AitelMood.WORRIED)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((mood, label) in MOODS) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                            AitelFace(mood, Modifier.size(44.dp))
                            Text(label, fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                // The practice band and the app icon (the launcher masks it; here, a circle and a rounded square)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f),
                    ) { Text("通話なし（練習）：電話はかけません", fontSize = 12.sp, modifier = Modifier.padding(8.dp)) }
                    for (shape in listOf(CircleShape, RoundedCornerShape(16.dp))) {
                        Image(
                            painterResource(R.drawable.ic_launcher_foreground),
                            contentDescription = null,
                            modifier = Modifier.size(64.dp).clip(shape).background(colorResource(R.color.ic_launcher_background)),
                        )
                    }
                }
            }
        }
    }
}

private val MOODS = listOf(
    AitelMood.HAPPY to "にこにこ",
    AitelMood.ASKING to "たずねる",
    AitelMood.WAITING to "待つ",
    AitelMood.GOING to "向かう",
    AitelMood.TALKING to "話す",
    AitelMood.RESTING to "休む",
    AitelMood.GREAT to "やった",
    AitelMood.WORRIED to "心配",
    AitelMood.SAD to "しょんぼり",
    AitelMood.SORRY to "ごめんなさい",
)

@Preview(name = "AITEL 明るい", locale = "ja", showBackground = true, widthDp = 411, heightDp = 1200)
@Composable
internal fun AitelLookLight() = Look(dark = false)

@Preview(name = "AITEL 暗い", locale = "ja", showBackground = true, widthDp = 411, heightDp = 1200)
@Composable
internal fun AitelLookDark() = Look(dark = true)
