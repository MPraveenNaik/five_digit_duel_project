package com.digitduel.game

import kotlin.random.Random

/**
 * Core rules, validation, guess evaluation, protocol helpers, and offline bot logic for Digit Duel.
 */
object GameLogic {

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
        data object Ready : ProtocolMessage()
        data class Guess(val digits: String) : ProtocolMessage()
        data class Feedback(val digits: String, val codesCsv: String) : ProtocolMessage()
        data class Reveal(val digits: String) : ProtocolMessage()
        data object PlayAgain : ProtocolMessage()
    }

    fun validateNumber(input: String): ValidationResult {
        if (input.length != NUMBER_LENGTH) {
            return ValidationResult.Invalid("Enter exactly $NUMBER_LENGTH digits.")
        }
        if (!input.all { it in '0'..'9' }) {
            return ValidationResult.Invalid("Only digits 0–9 are allowed.")
        }
        if (input.toSet().size != NUMBER_LENGTH) {
            return ValidationResult.Invalid("All $NUMBER_LENGTH digits must be unique (no repeats).")
        }
        return ValidationResult.Valid
    }

    fun isValidNumber(input: String): Boolean = validateNumber(input) is ValidationResult.Valid

    fun evaluateGuess(secret: String, guess: String): GuessEvaluation {
        require(isValidNumber(secret)) { "Secret must be a valid 5-digit unique number: '$secret'" }
        require(isValidNumber(guess)) { "Guess must be a valid 5-digit unique number: '$guess'" }

        val results = List(NUMBER_LENGTH) { index ->
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

    fun decodeFeedback(guess: String, codesCsv: String): GuessEvaluation? {
        if (!isValidNumber(guess)) return null
        val tokens = codesCsv.split(",")
        if (tokens.size != NUMBER_LENGTH) return null

        val results = ArrayList<DigitFeedback>(NUMBER_LENGTH)
        for (i in 0 until NUMBER_LENGTH) {
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

    fun generateRandomSecret(random: Random = Random.Default): String =
        ('0'..'9').shuffled(random).take(NUMBER_LENGTH).joinToString("")

    fun generateBotGuess(
        history: List<GuessEvaluation>,
        random: Random = Random.Default
    ): String {
        if (history.isEmpty()) {
            return generateRandomSecret(random)
        }
        val candidates = ArrayList<String>()
        val digits = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9')
        val buffer = CharArray(NUMBER_LENGTH)

        fun search(depth: Int, usedMask: Int) {
            if (depth == NUMBER_LENGTH) {
                val candidate = String(buffer)
                if (history.all { matchesEvaluation(candidate, it) }) {
                    candidates.add(candidate)
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
        return if (candidates.isNotEmpty()) {
            candidates[random.nextInt(candidates.size)]
        } else {
            generateRandomSecret(random)
        }
    }

    private fun matchesEvaluation(candidate: String, evaluation: GuessEvaluation): Boolean {
        for (i in 0 until NUMBER_LENGTH) {
            val feedback = evaluation.results[i]
            val g = feedback.digit
            when (feedback.status) {
                DigitStatus.CORRECT_PLACE -> if (candidate[i] != g) return false
                DigitStatus.WRONG_PLACE -> if (candidate[i] == g || !candidate.contains(g)) return false
                DigitStatus.DOES_NOT_EXIST -> if (candidate.contains(g)) return false
            }
        }
        return true
    }

    fun formatHello(playerName: String): String {
        val clean = playerName.replace("|", " ").replace("\r", "").replace("\n", " ").trim()
            .ifEmpty { "Player" }
        return "HELLO|$clean"
    }

    fun parseProtocolLine(rawLine: String): ProtocolMessage? {
        val line = rawLine.trim()
        if (line.isEmpty()) return null
        val parts = line.split("|")
        return when (parts[0]) {
            "HELLO" -> {
                val name = parts.drop(1).joinToString(" ").trim().ifEmpty { "Opponent" }
                ProtocolMessage.Hello(name)
            }
            "READY" -> if (parts.size == 1) ProtocolMessage.Ready else null
            "GUESS" -> {
                if (parts.size == 2 && isValidNumber(parts[1])) {
                    ProtocolMessage.Guess(parts[1])
                } else {
                    null
                }
            }
            "FEEDBACK" -> {
                if (parts.size == 3 && isValidNumber(parts[1])) {
                    ProtocolMessage.Feedback(parts[1], parts[2])
                } else {
                    null
                }
            }
            "REVEAL" -> {
                if (parts.size == 2 && isValidNumber(parts[1])) {
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
