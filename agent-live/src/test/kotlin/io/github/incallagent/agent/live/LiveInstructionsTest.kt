// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.agent.live

import io.github.incallagent.core.policy.Purpose
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveInstructionsTest {
    private val policy = TestPolicy.example()
    private val text = LiveInstructions.render(policy, TestPolicy.today)

    private fun has(s: String) = assertTrue("missing: $s\n---\n$text", text.contains(s))

    @Test
    fun containsTheFixedRules() {
        has("今日は 2026年11月7日(土) です。")
        has("「お忙しいところ失礼いたします。こちらはAIによる代理のお電話です。」")
        has("人の名前で名乗らない")
        has("「その点は確認して、改めてこちらから折り返しご連絡いたします。」")
        for (tool in LiveTools.NAMES) has(tool)
        has("聞かれても伝えない：クレジットカード番号、銀行口座")
        has("留守番電話につながったら、何も残さずに")
    }

    @Test
    fun containsThePolicyFacts() {
        has("「ビストロ・サンプル」")
        has("1. 2026年11月14日(土) 19:00 から")
        has("2. 2026年11月14日(土) 18:30〜20:00 の間に始まる時間")
        has("- 禁煙席：必須")
        has("- 人数は 2〜3名なら受けてよい")
        has("- 金額：1人 8000円まで")
        has("- キャンセル料などの条件への同意：しない")
        has("- 予約名：ヤマダ（名乗りには使わない）")
        has("- 連絡先の電話番号：090-0000-0000")
    }

    /** docs/live-agent.md section 3 shows the full text; keep it identical. */
    @Test
    fun documentationMatchesOutput() {
        val doc = java.io.File("../docs/live-agent.md").readText().replace("\r\n", "\n")
        val block = Regex("```\n(あなたは、.*?)```", RegexOption.DOT_MATCHES_ALL).find(doc)
            ?: throw AssertionError("instruction example block not found in docs/live-agent.md")
        org.junit.Assert.assertEquals(text, block.groupValues[1])
    }

    @Test
    fun callLimitIsNotRoundedDown() {
        org.junit.Assert.assertEquals("5 分", LiveInstructions.duration(300))
        org.junit.Assert.assertEquals("1 分 30 秒", LiveInstructions.duration(90))
        org.junit.Assert.assertEquals("45 秒", LiveInstructions.duration(45))
        val short = policy.copy(conversation = policy.conversation.copy(maxCallSeconds = 45))
        org.junit.Assert.assertTrue(LiveInstructions.render(short, TestPolicy.today).contains("9. 通話は 45 秒以内に終える。"))
    }

    @Test
    fun theBudgetLineIsLeftOutOfACancellationOnly() {
        fun textFor(purpose: Purpose) = LiveInstructions.render(policy.copy(purpose = purpose), TestPolicy.today)
        val withoutBudget = policy.copy(authority = policy.authority.copy(maxBudgetPerPersonYen = null))

        assertFalse(textFor(Purpose.CANCEL).contains("- 金額："))
        assertFalse(LiveInstructions.render(withoutBudget.copy(purpose = Purpose.CANCEL), TestPolicy.today).contains("- 金額："))
        assertTrue(textFor(Purpose.CANCEL).contains("- キャンセル料などの条件への同意：しない"))
        assertTrue(textFor(Purpose.INQUIRY).contains("- 金額：1人 8000円まで"))
        assertTrue(LiveInstructions.render(withoutBudget.copy(purpose = Purpose.INQUIRY), TestPolicy.today).contains("- 金額：決めない"))
        assertTrue(textFor(Purpose.CHANGE).contains("- 金額：1人 8000円まで"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unapprovedPolicyIsRejected() {
        LiveInstructions.render(policy.copy(approval = null), TestPolicy.today)
    }
}
