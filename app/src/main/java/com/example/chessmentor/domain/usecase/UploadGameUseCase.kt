package com.example.chessmentor.domain.usecase

import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.repository.GameRepository
import com.example.chessmentor.domain.repository.UserRepository
import com.example.chessmentor.domain.util.ChessMoveUtils

/**
 * Use Case: Загрузка партии для анализа
 */
class UploadGameUseCase(
    private val gameRepository: GameRepository,
    private val userRepository: UserRepository
) {

    data class Input(
        val userId: Long,
        val pgnData: String,
        val playerColor: ChessColor,
        val timeControl: String? = null,
        val playedAt: Long = System.currentTimeMillis()
    )

    sealed class Result {
        data class Success(val game: Game) : Result()
        data class Error(val message: String) : Result()
    }

    companion object {
        const val MIN_PGN_LENGTH = 5
        const val MAX_PGN_LENGTH = 50_000
    }

    suspend fun execute(input: Input): Result {
        userRepository.findById(input.userId)
            ?: return Result.Error("Пользователь не найден")

        val pgnValidation = validatePgn(input.pgnData)
        if (!pgnValidation.isValid) {
            return Result.Error(pgnValidation.errorMessage ?: "Некорректный PGN")
        }

        val game = Game(
            userId = input.userId,
            pgnData = input.pgnData.trim(),
            playerColor = input.playerColor,
            timeControl = input.timeControl,
            playedAt = input.playedAt
        )

        val savedGame = gameRepository.save(game)
        return Result.Success(savedGame)
    }

    private fun validatePgn(pgnData: String): PgnValidationResult {
        val trimmed = pgnData.trim()
        if (trimmed.isEmpty()) {
            return PgnValidationResult(false, "PGN не может быть пустым")
        }

        if (trimmed.length < MIN_PGN_LENGTH) {
            return PgnValidationResult(false, "PGN слишком короткий")
        }

        if (trimmed.length > MAX_PGN_LENGTH) {
            return PgnValidationResult(false, "PGN слишком длинный (максимум $MAX_PGN_LENGTH символов)")
        }

        return try {
            ChessMoveUtils.normalizePgn(trimmed)
            PgnValidationResult(true)
        } catch (e: IllegalArgumentException) {
            PgnValidationResult(false, e.message ?: "Некорректный формат PGN")
        }
    }

    private data class PgnValidationResult(
        val isValid: Boolean,
        val errorMessage: String? = null
    )
}
