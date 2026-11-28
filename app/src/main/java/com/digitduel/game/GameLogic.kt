package com.digitduel.game

import kotlin.random.Random

/**
 * Core rules, validation, guess evaluation, protocol helpers, and offline bot logic for Digit Duel.
 * Supports configurable secret lengths from 4 to 7 unique digits.
 */
object GameLogic {

    const val MIN_DIGITS = 4
    const val MAX_DIGITS = 7
    const val NUMBER_LENGTH = 5
    const val ROOM_CODE_LENGTH = 6
    private const val ROOM_CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val ENVELOPE_PREFIX = "DD1"

    data class RoomEnvelope(
        val senderId: String,
        val seq: Int,
        val payload: String
    )

    enum class DigitStatus(val code: Char) {
        CORRECT_PLACE('C'),
        WRONG_PLACE('W'),
        DOES_NOT_EXIST('N');

        companion object {
            fun fromCode(code: Char): DigitStatus? = entries.firstOrNull { it.code == code }
        }
    }

    data class DigitFeedback(
        val digit: Char,
        val status: DigitStatus
    )

    data class GuessEvaluation(
        val guess: String,
        val results: List<DigitFeedback>,
        val summary: String,
        val isWin: Boolean
    )

    sealed class ValidationResult {
        data object Valid : ValidationResult()
        data class Invalid(val errorMessage: String) : ValidationResult()
    }

    sealed class ProtocolMessage {
        data class Hello(val playerName: String) : ProtocolMessage()
        data class Config(val digitCount: Int, val sessionMatches: Int) : ProtocolMessage()
        data object Ready : ProtocolMessage()
        data class Guess(val digits: String) : ProtocolMessage()
        data class Feedback(val digits: String, val codesCsv: String) : ProtocolMessage()
        data class Reveal(val digits: String) : ProtocolMessage()
        data object PlayAgain : ProtocolMessage()
    }

    fun isSupportedDigitCount(digitCount: Int): Boolean = digitCount in MIN_DIGITS..MAX_DIGITS

    fun validateNumber(input: String, digitCount: Int = NUMBER_LENGTH): ValidationResult {
        if (!isSupportedDigitCount(digitCount)) {
            return ValidationResult.Invalid("Code length must be between $MIN_DIGITS and $MAX_DIGITS digits.")
        }
        if (input.length != digitCount) {
            return ValidationResult.Invalid("Enter exactly $digitCount digits.")
        }
        if (!input.all { it in '0'..'9' }) {
            return ValidationResult.Invalid("Only digits 0–9 are allowed.")
        }
        if (input.toSet().size != digitCount) {
            return ValidationResult.Invalid("All $digitCount digits must be unique (no repeats).")
        }
        return ValidationResult.Valid
    }

    fun isValidNumber(input: String, digitCount: Int = NUMBER_LENGTH): Boolean =
        validateNumber(input, digitCount) is ValidationResult.Valid

    fun evaluateGuess(
        secret: String,
        guess: String,
        digitCount: Int = secret.length
    ): GuessEvaluation {
        require(isSupportedDigitCount(digitCount)) { "Unsupported digit count: $digitCount" }
        require(isValidNumber(secret, digitCount)) {
            "Secret must be a valid $digitCount-digit unique number: '$secret'"
        }
        require(isValidNumber(guess, digitCount)) {
            "Guess must be a valid $digitCount-digit unique number: '$guess'"
        }

        val results = List(digitCount) { index ->
            val digit = guess[index]
            val status = when {
                digit == secret[index] -> DigitStatus.CORRECT_PLACE
                secret.contains(digit) -> DigitStatus.WRONG_PLACE
                else -> DigitStatus.DOES_NOT_EXIST
            }
            DigitFeedback(digit = digit, status = status)
        }
        val isWin = results.all { it.status == DigitStatus.CORRECT_PLACE }
        return GuessEvaluation(
            guess = guess,
            results = results,
            summary = buildSummary(results),
            isWin = isWin
        )
    }

    fun buildSummary(results: List<DigitFeedback>): String {
        val correct = results
            .filter { it.status == DigitStatus.CORRECT_PLACE }
            .map { it.digit }
        val wrongPlace = results
            .filter { it.status == DigitStatus.WRONG_PLACE }
            .map { it.digit }
        val absent = results
            .filter { it.status == DigitStatus.DOES_NOT_EXIST }
            .map { it.digit }

        val parts = mutableListOf<String>()
        if (correct.isNotEmpty()) {
            val prefix = correct.joinToString(", ")
            parts += if (correct.size == 1) {
                "$prefix exists & correct place"
            } else {
                "$prefix exist & correct place"
            }
        }
        if (wrongPlace.isNotEmpty()) {
            val prefix = wrongPlace.joinToString(", ")
            parts += if (wrongPlace.size == 1) {
                "$prefix exists (wrong place)"
            } else {
                "$prefix exist (wrong place)"
            }
        }
        if (absent.isNotEmpty()) {
            val prefix = absent.joinToString(", ")
            parts += if (absent.size == 1) {
                "$prefix does not exist"
            } else {
                "$prefix do not exist"
            }
        }
        return parts.joinToString(" \u2022 ")
    }

    fun encodeFeedbackCodes(results: List<DigitFeedback>): String =
        results.joinToString(",") { it.status.code.toString() }

    fun decodeFeedback(
        guess: String,
        codesCsv: String,
        digitCount: Int = guess.length
    ): GuessEvaluation? {
        if (!isSupportedDigitCount(digitCount) || !isValidNumber(guess, digitCount)) return null
        val tokens = codesCsv.split(",")
        if (tokens.size != digitCount) return null

        val results = ArrayList<DigitFeedback>(digitCount)
        for (i in 0 until digitCount) {
            val token = tokens[i].trim()
            if (token.length != 1) return null
            val status = DigitStatus.fromCode(token[0]) ?: return null
            results.add(DigitFeedback(digit = guess[i], status = status))
        }
        val isWin = results.all { it.status == DigitStatus.CORRECT_PLACE }
        return GuessEvaluation(
            guess = guess,
            results = results,
            summary = buildSummary(results),
            isWin = isWin
        )
    }

    fun generateRandomSecret(
        digitCount: Int = NUMBER_LENGTH,
        random: Random = Random.Default
    ): String {
        require(isSupportedDigitCount(digitCount)) { "Unsupported digit count: $digitCount" }
        return ('0'..'9').shuffled(random).take(digitCount).joinToString("")
    }

    fun generateBotGuess(
        history: List<GuessEvaluation>,
        digitCount: Int = NUMBER_LENGTH,
        random: Random = Random.Default
    ): String {
        require(isSupportedDigitCount(digitCount)) { "Unsupported digit count: $digitCount" }
        if (history.isEmpty()) {
            return generateRandomSecret(digitCount, random)
        }
        val digits = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9')
        val buffer = CharArray(digitCount)
        var matchCount = 0
        var chosen: String? = null

        fun search(depth: Int, usedMask: Int) {
            if (depth == digitCount) {
                if (history.all { matchesEvaluation(buffer, digitCount, it) }) {
                    matchCount++
                    if (random.nextInt(matchCount) == 0) {
                        chosen = String(buffer, 0, digitCount)
                    }
                }
                return
            }
            for (d in 0..9) {
                val bit = 1 shl d
                if ((usedMask and bit) == 0) {
                    buffer[depth] = digits[d]
                    search(depth + 1, usedMask or bit)
                }
            }
        }

        search(0, 0)
        return chosen ?: generateRandomSecret(digitCount, random)
    }

    private fun matchesEvaluation(
        candidate: CharArray,
        digitCount: Int,
        evaluation: GuessEvaluation
    ): Boolean {
        if (evaluation.results.size != digitCount) return false
        for (i in 0 until digitCount) {
            val feedback = evaluation.results[i]
            val g = feedback.digit
            when (feedback.status) {
                DigitStatus.CORRECT_PLACE -> if (candidate[i] != g) return false
                DigitStatus.WRONG_PLACE -> {
                    if (candidate[i] == g) return false
                    var foundElsewhere = false
                    for (j in 0 until digitCount) {
                        if (candidate[j] == g) {
                            foundElsewhere = true
                            break
                        }
                    }
                    if (!foundElsewhere) return false
                }
                DigitStatus.DOES_NOT_EXIST -> {
                    for (j in 0 until digitCount) {
                        if (candidate[j] == g) return false
                    }
                }
            }
        }
        return true
    }

    fun formatHello(playerName: String): String {
        val clean = playerName.replace("|", " ").replace("\r", "").replace("\n", " ").trim()
            .ifEmpty { "Player" }
        return "HELLO|$clean"
    }

    fun formatConfig(digitCount: Int, sessionMatches: Int): String =
        "CONFIG|$digitCount|$sessionMatches"

    fun parseProtocolLine(rawLine: String, expectedDigitCount: Int? = null): ProtocolMessage? {
        val line = rawLine.trim()
        if (line.isEmpty()) return null
        val parts = line.split("|")
        return when (parts[0]) {
            "HELLO" -> {
                val name = parts.drop(1).joinToString(" ").trim().ifEmpty { "Opponent" }
                ProtocolMessage.Hello(name)
            }
            "CONFIG" -> {
                if (parts.size != 3) return null
                val digits = parts[1].toIntOrNull() ?: return null
                val matches = parts[2].toIntOrNull() ?: return null
                if (!isSupportedDigitCount(digits) || matches !in listOf(1, 3, 5)) return null
                ProtocolMessage.Config(digits, matches)
            }
            "READY" -> if (parts.size == 1) ProtocolMessage.Ready else null
            "GUESS" -> {
                if (parts.size != 2) return null
                val len = expectedDigitCount ?: parts[1].length
                if (isSupportedDigitCount(len) && isValidNumber(parts[1], len)) {
                    ProtocolMessage.Guess(parts[1])
                } else {
                    null
                }
            }
            "FEEDBACK" -> {
                if (parts.size != 3) return null
                val len = expectedDigitCount ?: parts[1].length
                if (isSupportedDigitCount(len) && isValidNumber(parts[1], len)) {
                    ProtocolMessage.Feedback(parts[1], parts[2])
                } else {
                    null
                }
            }
            "REVEAL" -> {
                if (parts.size != 2) return null
                val len = expectedDigitCount ?: parts[1].length
                if (isSupportedDigitCount(len) && isValidNumber(parts[1], len)) {
                    ProtocolMessage.Reveal(parts[1])
                } else {
                    null
                }
            }
            "PLAY_AGAIN" -> if (parts.size == 1) ProtocolMessage.PlayAgain else null
            else -> null
        }
    }

    fun generateRoomCode(
        prefix: Char = 'E',
        random: Random = Random.Default
    ): String {
        val sb = StringBuilder(ROOM_CODE_LENGTH)
        sb.append(prefix.uppercaseChar())
        repeat(ROOM_CODE_LENGTH - 1) {
            sb.append(ROOM_CODE_ALPHABET[random.nextInt(ROOM_CODE_ALPHABET.length)])
        }
        return sb.toString()
    }

    fun normalizeRoomCode(raw: String): String =
        raw.trim().uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

    fun isValidRoomCode(raw: String): Boolean {
        val normalized = normalizeRoomCode(raw)
        return normalized.length == ROOM_CODE_LENGTH
    }

    fun brokerIndexForRoomCode(code: String): Int {
        val normalized = normalizeRoomCode(code)
        if (normalized.isEmpty()) return 0
        return when (normalized[0]) {
            'H' -> 1
            'M' -> 2
            else -> 0
        }
    }

    fun formatRoomEnvelope(senderId: String, seq: Int, payload: String): String {
        val cleanPayload = payload.replace("\r", "").replace("\n", " ")
        return "$ENVELOPE_PREFIX|$senderId|$seq|$cleanPayload"
    }

    fun parseRoomEnvelope(raw: String): RoomEnvelope? {
        val parts = raw.split("|", limit = 4)
        if (parts.size != 4 || parts[0] != ENVELOPE_PREFIX) return null
        val senderId = parts[1].trim()
        val seq = parts[2].toIntOrNull() ?: return null
        val payload = parts[3]
        if (senderId.isEmpty() || payload.isEmpty()) return null
        return RoomEnvelope(senderId = senderId, seq = seq, payload = payload)
    }
}
