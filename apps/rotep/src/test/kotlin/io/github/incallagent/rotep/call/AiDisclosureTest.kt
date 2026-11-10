// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiDisclosureTest {
    @Test
    fun theWaysTheTranscriptCanWriteAi() {
        for (t in listOf(
            "こちらはAIによる代理のお電話です。",
            "こちらはＡＩによる代理のお電話です。",
            "こちらはエーアイによる代理のお電話です。",
            "こちらはエイアイによる代理のお電話です。",
            "こちらはｴｰｱｲによる代理のお電話です。",
            "こちらはエー・アイによる代理のお電話です。",
            "こちらはえーあいによる代理のお電話です。",
            "人工知能がお電話しています。",
            "A.I. の代理です",
            "ai です",
        )) {
            assertTrue(t, AiDisclosure.mentionsAi(t))
        }
    }

    @Test
    fun lettersInsideAWordAreNotAi() {
        for (t in listOf(
            "お忙しいところ失礼いたします。",
            "メールでご連絡します",
            "MAIL でお送りします",
            "RAIN",
            "AIR",
            "空いている時間はありますか",
            "ええ、あいにく満席です",
            "まえあいさつ",
        )) {
            assertFalse(t, AiDisclosure.mentionsAi(t))
        }
    }

    // --- the shop's own name ---

    @Test
    fun theShopsNameIsNotTheAiSayingWhatItIs() {
        for ((shop, said) in listOf(
            "サロンAI" to "サロンAIさんでしょうか",
            "サロンＡＩ" to "サロンAIさんでしょうか",
            "AI歯科" to "AI歯科さんでしょうか",
            "AI歯科" to "エーアイ歯科さんでしょうか",
            "AI歯科 渋谷院" to "ＡＩ歯科さんでしょうか",
            "Hair Salon AI" to "HAIR SALON AI さんでしょうか",
            "ヘアサロン・AI" to "ヘアサロン AI さんでしょうか",
            "A.I.クリニック" to "AIクリニックさんでしょうか",
        )) {
            assertFalse("$shop: $said", AiDisclosure.mentionsAi(said, shop))
        }
    }

    @Test
    fun aDisclosureAfterTheShopsNameCounts() {
        assertTrue(AiDisclosure.mentionsAi("サロンAIさんでしょうか。こちらはAIによる代理のお電話です。", "サロンAI"))
    }

    @Test
    fun beforeAShopThatBeginsWithAiTheDisclosureCountsAsSoonAsItDiffers() {
        assertTrue(AiDisclosure.mentionsAi("こちらはAIです", "AI歯科"))
        assertTrue("the end of a sentence", AiDisclosure.mentionsAi("こちらはAI。", "AI歯科"))
    }

    @Test
    fun aTranscriptInPiecesIsJudgedAgainAsItGrows() {
        val said = StringBuilder()
        for (piece in listOf("A", "I", "歯", "科", "さんでしょうか")) {
            said.append(piece)
            assertFalse("$said", AiDisclosure.mentionsAi(said, "AI歯科"))
        }
        said.append("。こちらはAI")
        assertFalse("it may still be the shop again", AiDisclosure.mentionsAi(said, "AI歯科"))
        said.append("による代理です")
        assertTrue("$said", AiDisclosure.mentionsAi(said, "AI歯科"))
    }

    @Test
    fun aShopWithoutAiChangesNothing() {
        assertTrue(AiDisclosure.mentionsAi("こちらはAIによる代理のお電話です。", "レストラン・サンプル"))
        assertTrue(AiDisclosure.mentionsAi("こちらはAI", "レストラン・サンプル"))
        assertFalse(AiDisclosure.mentionsAi("お忙しいところ失礼いたします。", "レストラン・サンプル"))
    }

    @Test
    fun aShopCalledOnlyAiStillLetsTheDisclosureCount() {
        assertTrue(AiDisclosure.mentionsAi("こちらはAIによる代理のお電話です。", "AI"))
    }

    @Test
    fun theCueHasTheFormOfTheOpeningCue() {
        val disclosure = "お忙しいところ失礼いたします。こちらはAIによる代理のお電話です。"
        val content = Json.parseToJsonElement(AiDisclosure.cue(disclosure)).jsonObject.getValue("clientContent").jsonObject
        val turn = content.getValue("turns").jsonArray.single().jsonObject
        assertEquals("user", turn.getValue("role").jsonPrimitive.content)
        val text = turn.getValue("parts").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
        assertTrue(text, text.contains(disclosure))
        assertTrue(content.getValue("turnComplete").jsonPrimitive.boolean)
    }
}
