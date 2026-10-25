package org.schabi.newpipe.local.background

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException

class BotBlockGuardTest {
    @Test
    fun detectsBotBlockAlsoWhenWrapped() {
        val block = SignInConfirmNotBotException("Sign in to confirm that you're not a bot")
        assertTrue(BotBlockGuard.isBotBlock(block))
        assertTrue(BotBlockGuard.isBotBlock(RuntimeException(IOException(block))))
    }

    @Test
    fun otherErrorsAreNotBotBlocks() {
        assertFalse(BotBlockGuard.isBotBlock(IOException("timeout")))
        assertFalse(BotBlockGuard.isBotBlock(null))
    }
}
