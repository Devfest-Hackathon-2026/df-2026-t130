// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui

import io.github.incallagent.rotep.app.UserMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The key words of the screens (res/values/strings.xml). Other texts quote some of them word for word
 * (the notification of CallService, the instructions of ManualCallControl), so they must not drift,
 * and "ミュート" is not used (it does not say which microphone).
 */
class StringsXmlTest {
    private val strings: Map<String, String> by lazy {
        // Unit tests run in the module's directory
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/values/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        (0 until nodes.length).associate { i ->
            val n = nodes.item(i)
            n.attributes.getNamedItem("name").nodeValue to n.textContent
        }
    }

    @Test
    fun theQuotedWordsAreExactlyTheOnesOtherTextsUse() {
        assertEquals("AI を止めて本人に代わる", strings["stop_ai"])
        assertEquals("相手が出た", strings["answered"])
        assertEquals("切った", strings["hung_up"])
        assertEquals("承認して電話する", strings["approve_and_call"])
    }

    @Test
    fun theNameIsTheOneTheOwnerChose() {
        // "アイてる!? - AITEL -": the title on every screen is app_name, then app_title_sub (AitelTitle);
        // the launcher shows app_name alone
        assertEquals("アイてる!?", strings["app_name"])
        assertEquals("- AITEL -", strings["app_title_sub"])
        assertEquals("アイてる!? - AITEL -", "${strings["app_name"]} ${strings["app_title_sub"]}")
        // The name the permission message tells the user to look for in the phone's settings
        assertEquals(strings["app_name"], UserMessages.APP_NAME)
    }

    @Test
    fun noScreenWordSaysMute() {
        for ((name, text) in strings) assertFalse("$name: $text", text.contains("ミュート"))
    }
}
