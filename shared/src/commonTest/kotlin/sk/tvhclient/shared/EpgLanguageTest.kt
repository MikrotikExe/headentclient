package sk.tvhclient.shared

import kotlin.test.Test
import kotlin.test.assertEquals

/** M715: HTSP EPG language list = the client's language, then the server's defaults (like HTTP). */
class EpgLanguageTest {
    @Test
    fun serverLanguagesFollowTheClientLanguage() {
        val old = ClientIdent.lang2
        try {
            ClientIdent.lang2 = "sk,en"
            assertEquals("sk,cze,eng", ClientIdent.htspEpgLanguage("cze,eng"))
            assertEquals("sk,en", ClientIdent.htspEpgLanguage(null))
            assertEquals("sk,en", ClientIdent.htspEpgLanguage(" "))
            ClientIdent.lang2 = "en"
            assertEquals("en,ger", ClientIdent.htspEpgLanguage("ger"))
            ClientIdent.lang2 = ""
            assertEquals("cze", ClientIdent.htspEpgLanguage("cze"))
        } finally { ClientIdent.lang2 = old }
    }
}
