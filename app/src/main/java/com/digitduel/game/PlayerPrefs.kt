package com.digitduel.game

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the local player's display name and match statistics using SharedPreferences.
 */
class PlayerPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getPlayerName(): String {
        val stored = prefs.getString(KEY_PLAYER_NAME, DEFAULT_NAME) ?: DEFAULT_NAME
        return sanitizeName(stored)
    }

    fun setPlayerName(name: String): String {
        val clean = sanitizeName(name)
        prefs.edit().putString(KEY_PLAYER_NAME, clean).apply()
        return clean
    }

    fun getWins(): Int = prefs.getInt(KEY_WINS, 0)

    fun getGamesPlayed(): Int = prefs.getInt(KEY_GAMES_PLAYED, 0)

    fun recordGameResult(won: Boolean) {
        val currentGames = getGamesPlayed()
        val currentWins = getWins()
        prefs.edit()
            .putInt(KEY_GAMES_PLAYED, currentGames + 1)
            .putInt(KEY_WINS, if (won) currentWins + 1 else currentWins)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "digit_duel_prefs"
        private const val KEY_PLAYER_NAME = "player_name"
        private const val KEY_WINS = "wins"
        private const val KEY_GAMES_PLAYED = "games_played"
        private const val DEFAULT_NAME = "Player"
        private const val MAX_NAME_LENGTH = 24

        fun sanitizeName(raw: String): String {
            val cleaned = raw
                .replace("|", " ")
                .replace("\r", "")
                .replace("\n", " ")
                .trim()
                .take(MAX_NAME_LENGTH)
            return cleaned.ifEmpty { DEFAULT_NAME }
        }
    }
}
