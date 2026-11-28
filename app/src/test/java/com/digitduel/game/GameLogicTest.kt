package com.digitduel.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GameLogicTest {

    @Test
    fun validateNumber_acceptsUniqueFiveDigitsIncludingLeadingZero() {
        assertTrue(GameLogic.isValidNumber("01234"))
        assertTrue(GameLogic.isValidNumber("54612"))
        assertTrue(GameLogic.isValidNumber("98760"))
    }

    @Test
    fun validateNumber_rejectsWrongLengthAndNonDigits() {
        assertFalse(GameLogic.isValidNumber(""))
        assertFalse(GameLogic.isValidNumber("1234"))
        assertFalse(GameLogic.isValidNumber("123456"))
        assertFalse(GameLogic.isValidNumber("12a45"))
        assertFalse(GameLogic.isValidNumber("12 45"))

        val shortResult = GameLogic.validateNumber("1234")
        assertTrue(shortResult is GameLogic.ValidationResult.Invalid)
    }

    @Test
    fun validateNumber_rejectsRepeatingDigits() {
        assertFalse(GameLogic.isValidNumber("11234"))
        assertFalse(GameLogic.isValidNumber("01230"))
        assertFalse(GameLogic.isValidNumber("99999"))

        val repeatResult = GameLogic.validateNumber("54615")
        assertTrue(repeatResult is GameLogic.ValidationResult.Invalid)
    }

    @Test
    fun evaluateGuess_exactMatchIsWin() {
        val eval = GameLogic.evaluateGuess("04612", "04612")
        assertTrue(eval.isWin)
        assertTrue(eval.results.all { it.status == GameLogic.DigitStatus.CORRECT_PLACE })
        assertEquals("0, 4, 6, 1, 2 exist & correct place", eval.summary)
    }

    @Test
    fun evaluateGuess_matchesExactPromptExample() {
        val eval = GameLogic.evaluateGuess(secret = "54612", guess = "43716")

        assertFalse(eval.isWin)
        assertEquals(5, eval.results.size)

        assertEquals('4', eval.results[0].digit)
        assertEquals(GameLogic.DigitStatus.WRONG_PLACE, eval.results[0].status)

        assertEquals('3', eval.results[1].digit)
        assertEquals(GameLogic.DigitStatus.DOES_NOT_EXIST, eval.results[1].status)

        assertEquals('7', eval.results[2].digit)
        assertEquals(GameLogic.DigitStatus.DOES_NOT_EXIST, eval.results[2].status)

        assertEquals('1', eval.results[3].digit)
        assertEquals(GameLogic.DigitStatus.CORRECT_PLACE, eval.results[3].status)

        assertEquals('6', eval.results[4].digit)
        assertEquals(GameLogic.DigitStatus.WRONG_PLACE, eval.results[4].status)

        assertEquals(
            "1 exists & correct place \u2022 4, 6 exist (wrong place) \u2022 3, 7 do not exist",
            eval.summary
        )
    }

    @Test
    fun feedbackEncodingAndDecoding_roundTripsAccurately() {
        val original = GameLogic.evaluateGuess(secret = "54612", guess = "43716")
        val codes = GameLogic.encodeFeedbackCodes(original.results)
        assertEquals("W,N,N,C,W", codes)

        val decoded = GameLogic.decodeFeedback("43716", codes)
        assertNotNull(decoded)
        assertEquals(original, decoded)
    }

    @Test
    fun protocolParsing_handlesAllSupportedMessages() {
        assertEquals(
            GameLogic.ProtocolMessage.Hello("Alice"),
            GameLogic.parseProtocolLine("HELLO|Alice")
        )
        assertEquals(
            "HELLO|Alice Bob",
            GameLogic.formatHello("Alice|Bob\n")
        )
        assertEquals(
            GameLogic.ProtocolMessage.Ready,
            GameLogic.parseProtocolLine("READY")
        )
        assertEquals(
            GameLogic.ProtocolMessage.Guess("01234"),
            GameLogic.parseProtocolLine("GUESS|01234")
        )
        assertNull(GameLogic.parseProtocolLine("GUESS|11234"))
        assertEquals(
            GameLogic.ProtocolMessage.Feedback("43716", "W,N,N,C,W"),
            GameLogic.parseProtocolLine("FEEDBACK|43716|W,N,N,C,W")
        )
        assertEquals(
            GameLogic.ProtocolMessage.Reveal("54612"),
            GameLogic.parseProtocolLine("REVEAL|54612")
        )
        assertEquals(
            GameLogic.ProtocolMessage.PlayAgain,
            GameLogic.parseProtocolLine("PLAY_AGAIN")
        )
    }

    @Test
    fun generateRandomSecret_andBotGuess_alwaysProduceValidNumbersConsistentWithHistory() {
        val rng = Random(42)
        repeat(25) {
            val secret = GameLogic.generateRandomSecret(random = rng)
            assertTrue(GameLogic.isValidNumber(secret))
        }

        val target = "54612"
        val firstEval = GameLogic.evaluateGuess(target, "43716")
        val secondGuess = GameLogic.generateBotGuess(listOf(firstEval), random = rng)
        assertTrue(GameLogic.isValidNumber(secondGuess))
        // Must preserve '1' at index 3, include '4' and '6' not at indices 0/4, and exclude '3' and '7'.
        assertEquals('1', secondGuess[3])
        assertTrue(secondGuess.contains('4') && secondGuess[0] != '4')
        assertTrue(secondGuess.contains('6') && secondGuess[4] != '6')
        assertFalse(secondGuess.contains('3'))
        assertFalse(secondGuess.contains('7'))
    }

    @Test
    fun roomCodeAndEnvelopeHelpers_validateNormalizeAndRoundTrip() {
        val code = GameLogic.generateRoomCode(prefix = 'E', random = Random(7))
        assertEquals(6, code.length)
        assertEquals('E', code[0])
        assertTrue(GameLogic.isValidRoomCode(code))

        assertEquals("H7K9P2", GameLogic.normalizeRoomCode(" h7k9-p2 "))
        assertTrue(GameLogic.isValidRoomCode(" h7k9-p2 "))
        assertFalse(GameLogic.isValidRoomCode("123"))

        assertEquals(0, GameLogic.brokerIndexForRoomCode("E7K9P2"))
        assertEquals(1, GameLogic.brokerIndexForRoomCode("h7k9p2"))
        assertEquals(2, GameLogic.brokerIndexForRoomCode("M7K9P2"))

        val wire = GameLogic.formatRoomEnvelope("peer1234", 5, "GUESS|04612")
        val parsed = GameLogic.parseRoomEnvelope(wire)
        assertNotNull(parsed)
        assertEquals("peer1234", parsed!!.senderId)
        assertEquals(5, parsed.seq)
        assertEquals("GUESS|04612", parsed.payload)
        assertNull(GameLogic.parseRoomEnvelope("BAD|peer|1|hi"))
    }

    @Test
    fun variableDigitLengths_4to7_validateEvaluateAndSyncConfig() {
        for (len in 4..7) {
            val secret = GameLogic.generateRandomSecret(len, Random(len))
            assertEquals(len, secret.length)
            assertTrue(GameLogic.isValidNumber(secret, len))
            assertFalse(GameLogic.isValidNumber(secret, if (len == 4) 5 else 4))

            val winEval = GameLogic.evaluateGuess(secret, secret, len)
            assertTrue(winEval.isWin)
            assertEquals(len, winEval.results.size)

            val botGuess = GameLogic.generateBotGuess(listOf(winEval), len, Random(99))
            assertEquals(secret, botGuess)
        }

        assertFalse(GameLogic.isSupportedDigitCount(3))
        assertFalse(GameLogic.isSupportedDigitCount(8))

        val eval7 = GameLogic.evaluateGuess("0123456", "6193802", 7)
        assertFalse(eval7.isWin)
        assertEquals(7, eval7.results.size)
        val encoded = GameLogic.encodeFeedbackCodes(eval7.results)
        val decoded = GameLogic.decodeFeedback("6193802", encoded, 7)
        assertEquals(eval7, decoded)

        assertEquals(
            GameLogic.ProtocolMessage.Config(6, 5),
            GameLogic.parseProtocolLine(GameLogic.formatConfig(6, 5))
        )
        assertEquals(
            GameLogic.ProtocolMessage.Guess("0123456"),
            GameLogic.parseProtocolLine("GUESS|0123456", expectedDigitCount = 7)
        )
        assertNull(GameLogic.parseProtocolLine("GUESS|0123", expectedDigitCount = 7))
    }
}
