package com.example.chessmentor.domain.usecase

import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.domain.pgn.PgnParser
import com.example.chessmentor.domain.repository.GameRepository
import com.example.chessmentor.domain.repository.UserRepository

/**
 * Use Case: Загрузка одной партии или пакета партий (мульти-PGN) в библиотеку пользователя.
 *
 * Выполняет:
 * - Полную проверку легальности всех ходов партии на доске ([PgnParser.parseAndValidate]).
 * - Извлечение реальной даты партии ([UTCDate]/[Date]), контроля времени и дебюта (ECO + название).
 * - Автоопределение цвета игрока по никнейму (при пакетном импорте или при включённом автоопределении).
 * - Защиту от повторного импорта одинаковых партий по каноническому отпечатку.
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
        val playedAt: Long? = null,
        val autoDetectColor: Boolean = false,
        val playerNameHint: String? = null,
        val allowDuplicate: Boolean = false
    )

    sealed class Result {
        data class Success(val game: Game, val colorAutoDetected: Boolean = false) : Result()
        data class Duplicate(val existingGame: Game) : Result()
        data class Error(val message: String) : Result()
    }

    data class BatchInput(
        val userId: Long,
        val rawPgnArchive: String,
        val fallbackColor: ChessColor,
        val manualTimeControl: String? = null,
        val playerNameHint: String? = null,
        val autoDetectColor: Boolean = true,
        val skipDuplicates: Boolean = true,
        val startOffset: Int = 0
    )

    sealed class BatchResult {
        data class Success(
            val importedGames: List<Game>,
            val skippedDuplicates: Int,
            val failedCount: Int,
            val truncatedCount: Int,
            val autoDetectedColorsCount: Int,
            val firstError: String? = null,
            val startOffset: Int = 0,
            val processedCount: Int = 0,
            val totalInArchive: Int = 0,
            val remainingAfterWindow: Int = 0,
            val failedDetails: List<String> = emptyList()
        ) : BatchResult()

        data class Error(val message: String) : BatchResult()
    }

    companion object {
        const val MIN_PGN_LENGTH = 2
        const val MAX_PGN_LENGTH = PgnParser.MAX_SINGLE_PGN_CHARS
        const val MAX_ARCHIVE_LENGTH = PgnParser.MAX_ARCHIVE_CHARS
        const val MAX_BATCH_GAMES = PgnParser.MAX_BATCH_GAMES
    }

    suspend fun execute(input: Input): Result {
        val user = userRepository.findById(input.userId)
            ?: return Result.Error("Пользователь не найден")

        val trimmed = input.pgnData.trim()
        if (trimmed.isEmpty()) {
            return Result.Error("PGN не может быть пустым")
        }
        if (trimmed.length < MIN_PGN_LENGTH) {
            return Result.Error("PGN слишком короткий")
        }
        if (trimmed.length > MAX_PGN_LENGTH) {
            return Result.Error("PGN слишком длинный (максимум $MAX_PGN_LENGTH символов)")
        }

        val parsed = when (val res = PgnParser.parseAndValidate(trimmed)) {
            is PgnParser.ParseResult.Success -> res.game
            is PgnParser.ParseResult.Failure -> return Result.Error(res.errorMessage)
        }

        if (!input.allowDuplicate) {
            val existingGames = gameRepository.findByUserId(input.userId)
            val duplicate = findDuplicateGame(existingGames, parsed.fingerprint)
            if (duplicate != null) {
                return Result.Duplicate(duplicate)
            }
        }

        val candidateNames = buildCandidateNames(user, input.playerNameHint)
        val detectedColor = if (input.autoDetectColor) {
            PgnParser.detectPlayerColor(parsed.metadata, candidateNames)
        } else {
            null
        }
        val finalColor = detectedColor ?: input.playerColor

        val resolvedTimeControl = resolveTimeControl(input.timeControl, parsed)
        val resolvedPlayedAt = parsed.metadata.playedAtMillis ?: input.playedAt ?: 0L

        val game = Game(
            userId = input.userId,
            pgnData = parsed.enrichedPgn,
            playerColor = finalColor,
            timeControl = resolvedTimeControl,
            playedAt = resolvedPlayedAt
        )

        val savedGame = gameRepository.save(game)
        return Result.Success(savedGame, colorAutoDetected = detectedColor != null)
    }

    /**
     * Пакетный импорт PGN-текста или `.pgn`-файла (1..[MAX_BATCH_GAMES] партий).
     */
    suspend fun executeBatch(input: BatchInput): BatchResult {
        val user = userRepository.findById(input.userId)
            ?: return BatchResult.Error("Пользователь не найден")

        val raw = input.rawPgnArchive.trim()
        if (raw.isEmpty()) {
            return BatchResult.Error("Файл или текст PGN пуст")
        }
        if (raw.length > MAX_ARCHIVE_LENGTH) {
            return BatchResult.Error("Размер PGN превышает допустимый лимит (1.5 МБ)")
        }

        val allBlocks = PgnParser.splitGames(raw)
        if (allBlocks.isEmpty()) {
            return BatchResult.Error("В тексте не обнаружено ни одной партии")
        }

        val safeOffset = input.startOffset.coerceIn(0, allBlocks.size)
        val blocks = allBlocks.drop(safeOffset).take(MAX_BATCH_GAMES)
        val remainingAfterWindow = (allBlocks.size - (safeOffset + blocks.size)).coerceAtLeast(0)
        val truncatedCount = remainingAfterWindow

        val seenFingerprints = HashSet<String>()
        if (input.skipDuplicates) {
            for (existing in gameRepository.findByUserId(input.userId)) {
                PgnParser.computeFingerprintForStoredPgn(existing.pgnData)?.let { fp ->
                    seenFingerprints.add(fp)
                }
            }
        }

        val candidateNames = buildCandidateNames(user, input.playerNameHint)
        val imported = mutableListOf<Game>()
        var skippedDuplicates = 0
        var failedCount = 0
        var autoDetectedCount = 0
        var firstError: String? = null
        val failedDetails = mutableListOf<String>()

        for ((index, block) in blocks.withIndex()) {
            val globalNumber = safeOffset + index + 1
            val parseRes = PgnParser.parseAndValidate(block)
            if (parseRes is PgnParser.ParseResult.Failure) {
                failedCount++
                val meta = runCatching { PgnParser.extractMetadata(block) }.getOrNull()
                val playersLabel = if (meta != null && (meta.whiteName != null || meta.blackName != null)) {
                    " (${meta.whiteName ?: "?"} – ${meta.blackName ?: "?"})"
                } else {
                    ""
                }
                val errLine = "Партия #$globalNumber$playersLabel: ${parseRes.errorMessage}"
                if (firstError == null) {
                    firstError = errLine
                }
                if (failedDetails.size < 20) {
                    failedDetails.add(errLine)
                }
                continue
            }

            val parsed = (parseRes as PgnParser.ParseResult.Success).game
            if (input.skipDuplicates && !seenFingerprints.add(parsed.fingerprint)) {
                skippedDuplicates++
                continue
            }

            val detectedColor = if (input.autoDetectColor) {
                PgnParser.detectPlayerColor(parsed.metadata, candidateNames)
            } else {
                null
            }
            if (detectedColor != null) autoDetectedCount++

            val game = Game(
                userId = input.userId,
                pgnData = parsed.enrichedPgn,
                playerColor = detectedColor ?: input.fallbackColor,
                timeControl = resolveTimeControl(input.manualTimeControl, parsed),
                playedAt = parsed.metadata.playedAtMillis ?: 0L
            )
            imported.add(gameRepository.save(game))
        }

        if (imported.isEmpty()) {
            return when {
                skippedDuplicates > 0 && failedCount == 0 -> BatchResult.Success(
                    importedGames = emptyList(),
                    skippedDuplicates = skippedDuplicates,
                    failedCount = 0,
                    truncatedCount = truncatedCount,
                    autoDetectedColorsCount = 0,
                    startOffset = safeOffset,
                    processedCount = blocks.size,
                    totalInArchive = allBlocks.size,
                    remainingAfterWindow = remainingAfterWindow
                )
                firstError != null -> BatchResult.Error(firstError)
                else -> BatchResult.Error("Не удалось импортировать ни одной партии")
            }
        }

        return BatchResult.Success(
            importedGames = imported,
            skippedDuplicates = skippedDuplicates,
            failedCount = failedCount,
            truncatedCount = truncatedCount,
            autoDetectedColorsCount = autoDetectedCount,
            firstError = firstError,
            startOffset = safeOffset,
            processedCount = blocks.size,
            totalInArchive = allBlocks.size,
            remainingAfterWindow = remainingAfterWindow,
            failedDetails = failedDetails
        )
    }

    private fun findDuplicateGame(existingGames: List<Game>, targetFingerprint: String): Game? {
        for (game in existingGames) {
            val fp = PgnParser.computeFingerprintForStoredPgn(game.pgnData)
            if (fp == targetFingerprint) return game
        }
        return null
    }

    private fun buildCandidateNames(user: User, playerNameHint: String?): List<String> {
        val emailLocal = user.email.substringBefore('@').trim()
        return listOfNotNull(
            playerNameHint?.trim()?.takeIf { it.isNotEmpty() },
            user.nickname.trim().takeIf { it.isNotEmpty() },
            emailLocal.takeIf { it.length >= 3 }
        )
    }

    private fun resolveTimeControl(
        manualTimeControl: String?,
        parsed: PgnParser.ParsedPgnGame
    ): String? {
        val manualClean = manualTimeControl?.trim()?.takeIf { it.isNotEmpty() }
        if (manualClean != null) {
            return PgnParser.parseTimeControl(manualClean)?.fullLabel ?: manualClean
        }
        return parsed.metadata.timeControl?.fullLabel
    }
}
