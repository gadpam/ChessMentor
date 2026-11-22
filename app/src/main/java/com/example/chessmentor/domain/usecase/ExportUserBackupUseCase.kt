// domain/usecase/ExportUserBackupUseCase.kt
package com.example.chessmentor.domain.usecase

import com.example.chessmentor.domain.backup.UserProgressBackupCodec
import com.example.chessmentor.domain.backup.UserProgressBackupCodec.AnalyzedMoveEntry
import com.example.chessmentor.domain.backup.UserProgressBackupCodec.AttemptEntry
import com.example.chessmentor.domain.backup.UserProgressBackupCodec.BackupSnapshot
import com.example.chessmentor.domain.backup.UserProgressBackupCodec.GameEntry
import com.example.chessmentor.domain.backup.UserProgressBackupCodec.MistakeEntry
import com.example.chessmentor.domain.backup.UserProgressBackupCodec.PersonalExerciseEntry
import com.example.chessmentor.domain.backup.UserProgressBackupCodec.ProfileSection
import com.example.chessmentor.domain.entity.Exercise
import com.example.chessmentor.domain.repository.AnalyzedMoveRepository
import com.example.chessmentor.domain.repository.EngineSettingsRepository
import com.example.chessmentor.domain.repository.ExerciseRepository
import com.example.chessmentor.domain.repository.GameRepository
import com.example.chessmentor.domain.repository.MistakeRepository
import com.example.chessmentor.domain.repository.SessionRepository
import com.example.chessmentor.domain.repository.UserRepository
import com.example.chessmentor.domain.session.AppThemeMode
import com.example.chessmentor.domain.training.ExerciseMetadata
import java.time.ZoneId

/**
 * Формирует полный, защищённый контрольной суммой SHA-256 JSON-снимок прогресса пользователя
 * (партии, разборы Stockfish, ошибки, персональные задачи и история интервальных повторений SM-2).
 *
 * Никогда не экспортирует пароль или хеш пароля.
 */
class ExportUserBackupUseCase(
    private val userRepository: UserRepository,
    private val gameRepository: GameRepository,
    private val mistakeRepository: MistakeRepository,
    private val analyzedMoveRepository: AnalyzedMoveRepository,
    private val exerciseRepository: ExerciseRepository,
    private val sessionRepository: SessionRepository? = null,
    private val engineSettingsRepository: EngineSettingsRepository? = null
) {

    data class Input(
        val userId: Long,
        val nowMs: Long = System.currentTimeMillis(),
        val zoneId: ZoneId = ZoneId.systemDefault()
    )

    data class ExportedBackup(
        val jsonContent: String,
        val suggestedFileName: String,
        val gamesCount: Int,
        val analyzedGamesCount: Int,
        val personalExercisesCount: Int,
        val attemptsCount: Int
    )

    sealed class Result {
        data class Success(val backup: ExportedBackup) : Result()
        data class Error(val message: String) : Result()
    }

    suspend fun execute(input: Input): Result {
        val user = userRepository.findById(input.userId)
            ?: return Result.Error("Пользователь не найден")
        val userId = user.id
            ?: return Result.Error("Некорректный идентификатор пользователя")

        val orderedGames = gameRepository.findByUserId(userId)
            .sortedWith(compareBy({ it.playedAt }, { it.id ?: 0L }))
        val gameIds = orderedGames.mapNotNull { it.id }

        val movesByGame = if (gameIds.isNotEmpty()) {
            analyzedMoveRepository.findByGameIds(gameIds).groupBy { it.gameId }
        } else {
            emptyMap()
        }
        val mistakesByGame = if (gameIds.isNotEmpty()) {
            mistakeRepository.findByGameIds(gameIds).groupBy { it.gameId }
        } else {
            emptyMap()
        }

        val gameIndexById = HashMap<Long, Int>(orderedGames.size)
        val gameEntries = orderedGames.mapIndexed { idx, game ->
            val gid = game.id
            if (gid != null) {
                gameIndexById[gid] = idx
            }
            val moveEntries = (if (gid != null) movesByGame[gid] else null)
                .orEmpty()
                .sortedBy { it.moveIndex }
                .map { m ->
                    AnalyzedMoveEntry(
                        moveIndex = m.moveIndex,
                        moveNumber = m.moveNumber,
                        color = m.color,
                        quality = m.quality,
                        san = m.san,
                        bestMove = m.bestMove,
                        evalBefore = m.evalBefore,
                        evalAfter = m.evalAfter,
                        evalLoss = m.evalLoss,
                        comment = m.comment
                    )
                }
            val mistakeEntries = (if (gid != null) mistakesByGame[gid] else null)
                .orEmpty()
                .sortedBy { it.moveNumber }
                .map { mk ->
                    MistakeEntry(
                        themeId = mk.themeId,
                        moveNumber = mk.moveNumber,
                        color = mk.color,
                        evaluationLoss = mk.evaluationLoss,
                        mistakeType = mk.mistakeType,
                        bestMove = mk.bestMove,
                        userMove = mk.userMove,
                        comment = mk.comment,
                        fenBefore = mk.fenBefore
                    )
                }
            GameEntry(
                pgnData = game.pgnData,
                playerColor = game.playerColor,
                timeControl = game.timeControl,
                playedAt = game.playedAt,
                analysisStatus = game.analysisStatus,
                analyzedAt = game.analyzedAt,
                accuracy = game.accuracy,
                averageEvaluationLoss = game.averageEvaluationLoss,
                totalMistakes = game.totalMistakes,
                blundersCount = game.blundersCount,
                mistakesCount = game.mistakesCount,
                inaccuraciesCount = game.inaccuraciesCount,
                evaluationsJson = game.evaluationsJson,
                analyzedMoves = moveEntries,
                mistakes = mistakeEntries
            )
        }

        // Персональные задачи пользователя (с привязкой к индексу партии в снимке)
        val personalExercises = exerciseRepository.findByUserId(userId)
            .sortedWith(compareBy({ it.createdAt }, { it.id ?: 0L }))
        val personalIndexById = HashMap<Long, Int>(personalExercises.size)
        val exerciseEntries = personalExercises.mapIndexed { idx, ex ->
            ex.id?.let { personalIndexById[it] = idx }
            PersonalExerciseEntry(
                fenPosition = ex.fenPosition,
                solutionMoves = ex.solutionMoves,
                tacticalPattern = ex.tacticalPattern,
                patternClusterId = ex.patternClusterId,
                difficultyRating = ex.difficultyRating,
                patternFeatures = ex.patternFeatures,
                theme = ex.theme,
                rating = ex.rating,
                createdFrom = ex.createdFrom,
                sourceGameIndex = ex.sourceGameId?.let { gameIndexById[it] },
                sourceMoveNumber = ex.sourceMoveNumber,
                createdAt = ex.createdAt,
                timesShown = ex.timesShown,
                timesSolved = ex.timesSolved,
                successRate = ex.successRate
            )
        }

        // История попыток (как по персональным, так и по встроенным задачам банка)
        val attempts = exerciseRepository.getAttemptsByUserId(userId)
            .sortedWith(compareBy({ it.attemptedAt }, { it.id ?: 0L }))
        val referencedIds = attempts.map { it.exerciseId }.distinct()
        val exercisesById: Map<Long, Exercise> = if (referencedIds.isNotEmpty()) {
            exerciseRepository.findByIds(referencedIds)
                .mapNotNull { ex -> ex.id?.let { it to ex } }
                .toMap()
        } else {
            emptyMap()
        }

        val attemptEntries = attempts.mapNotNull { attempt ->
            val pIdx = personalIndexById[attempt.exerciseId]
            val ex = exercisesById[attempt.exerciseId]
            val curatedId = ex?.let { ExerciseMetadata.extractCuratedId(it) }
            val fen = ex?.fenPosition
            if (pIdx == null && curatedId == null && fen == null) {
                return@mapNotNull null
            }
            AttemptEntry(
                personalExerciseIndex = pIdx,
                curatedId = curatedId,
                exerciseFen = fen,
                solved = attempt.solved,
                timeSpentSeconds = attempt.timeSpentSeconds,
                movesMade = attempt.movesMade,
                attemptedAt = attempt.attemptedAt
            )
        }

        val profile = ProfileSection(
            nickname = user.nickname,
            rating = user.rating,
            isSoundEnabled = user.isSoundEnabled,
            preferredBoardTheme = user.preferredTheme,
            appThemeMode = sessionRepository?.themeMode?.value ?: AppThemeMode.DARK,
            chessUsernameHint = sessionRepository?.getChessUsernameHint(userId),
            engineSettings = engineSettingsRepository?.getSettings()
        )

        val snapshot = BackupSnapshot(
            exportedAtMs = input.nowMs,
            profile = profile,
            games = gameEntries,
            personalExercises = exerciseEntries,
            attempts = attemptEntries
        )

        val json = UserProgressBackupCodec.encode(snapshot, pretty = true)
        val fileName = UserProgressBackupCodec.suggestFileName(
            nickname = user.nickname,
            nowMs = input.nowMs,
            zoneId = input.zoneId
        )

        return Result.Success(
            ExportedBackup(
                jsonContent = json,
                suggestedFileName = fileName,
                gamesCount = gameEntries.size,
                analyzedGamesCount = orderedGames.count { it.isAnalyzed() },
                personalExercisesCount = exerciseEntries.size,
                attemptsCount = attemptEntries.size
            )
        )
    }
}
