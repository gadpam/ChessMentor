// domain/usecase/ImportUserBackupUseCase.kt
package com.example.chessmentor.domain.usecase

import com.example.chessmentor.domain.backup.UserProgressBackupCodec
import com.example.chessmentor.domain.backup.UserProgressBackupCodec.GameEntry
import com.example.chessmentor.domain.entity.AnalysisStatus
import com.example.chessmentor.domain.entity.AnalyzedMove
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Exercise
import com.example.chessmentor.domain.entity.ExerciseAttempt
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.entity.Mistake
import com.example.chessmentor.domain.entity.SkillLevel
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.domain.pgn.PgnParser
import com.example.chessmentor.domain.repository.AnalyzedMoveRepository
import com.example.chessmentor.domain.repository.EngineSettingsRepository
import com.example.chessmentor.domain.repository.ExerciseRepository
import com.example.chessmentor.domain.repository.GameRepository
import com.example.chessmentor.domain.repository.MistakeRepository
import com.example.chessmentor.domain.repository.SessionRepository
import com.example.chessmentor.domain.repository.TransactionRunner
import com.example.chessmentor.domain.repository.UserRepository
import com.example.chessmentor.domain.training.CuratedExerciseBank
import com.example.chessmentor.domain.training.ExerciseMetadata
import java.util.Locale
import kotlinx.coroutines.CancellationException

/**
 * Безопасно и идемпотентно восстанавливает прогресс пользователя из JSON-копии:
 * - проверяет формат, версию схемы и контрольную сумму SHA-256 до записи в БД;
 * - импортирует отсутствующие партии вместе с разбором Stockfish и ошибками;
 * - дообогащает локальные неразобранные партии анализом из копии (без создания дублей);
 * - перепривязывает персональные задачи к локальным ID партий;
 * - восстанавливает историю попыток SM-2/Лейтнера по персональным и встроенным задачам без дублей.
 */
class ImportUserBackupUseCase(
    private val userRepository: UserRepository,
    private val gameRepository: GameRepository,
    private val mistakeRepository: MistakeRepository,
    private val analyzedMoveRepository: AnalyzedMoveRepository,
    private val exerciseRepository: ExerciseRepository,
    private val sessionRepository: SessionRepository? = null,
    private val engineSettingsRepository: EngineSettingsRepository? = null,
    /**
     * Когда задана, все изменения БД применяются одной транзакцией: при сбое
     * посередине восстановление откатывается целиком, а не остаётся наполовину.
     */
    private val transactionRunner: TransactionRunner? = null
) {

    data class Input(
        val userId: Long,
        val rawJson: String,
        val restorePreferences: Boolean = true
    )

    data class ImportSummary(
        val updatedUser: User,
        val gamesImported: Int,
        val gamesAnalysisUpgraded: Int,
        val gamesSkippedDuplicate: Int,
        val exercisesImported: Int,
        val attemptsImported: Int,
        val attemptsSkippedDuplicate: Int,
        val preferencesUpdated: Boolean = false
    ) {
        val totalChanges: Int
            get() = gamesImported + gamesAnalysisUpgraded + exercisesImported + attemptsImported
    }

    sealed class Result {
        data class Success(val summary: ImportSummary) : Result()
        data class Error(val message: String) : Result()
    }

    private data class AttemptSignature(
        val exerciseId: Long,
        val attemptedAt: Long,
        val solved: Boolean,
        val timeSpentSeconds: Int,
        val movesMade: List<String>
    )

    suspend fun execute(input: Input): Result {
        // Транзакция захватывает разбор и проверку перед записью, чтобы не держать
        // блокировку БД во время полного SHA-256/parse большого JSON-файла.
        val snapshot = when (val decoded = UserProgressBackupCodec.decodeAndVerify(input.rawJson)) {
            is UserProgressBackupCodec.DecodeResult.Success -> decoded.snapshot
            is UserProgressBackupCodec.DecodeResult.Error -> return Result.Error(decoded.message)
        }

        val transaction = transactionRunner
        val result = if (transaction == null) {
            applySnapshot(input, snapshot)
        } else {
            try {
                transaction.run { applySnapshot(input, snapshot) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Транзакция уже откатила изменения БД: сообщаем, что копия не применена.
                return Result.Error(
                    "Восстановление прервано, изменения не применены: " +
                        (e.message ?: "неизвестная ошибка")
                )
            }
        }

        // Настройки вне БД пишутся только после успешной фиксации. SharedPreferences
        // живут вне транзакции Room, поэтому их запись внутри неё откатить нельзя:
        // тема и параметры движка изменились бы даже при проваленном восстановлении.
        return if (result is Result.Success && input.restorePreferences) {
            val offDbPrefsUpdated = applyOffDatabasePreferences(input.userId, snapshot.profile)
            Result.Success(
                result.summary.copy(
                    preferencesUpdated = result.summary.preferencesUpdated || offDbPrefsUpdated
                )
            )
        } else {
            result
        }
    }

    /**
     * Применяет настройки, которые хранятся не в Room: тему оформления, ник источника
     * Chess.com и параметры движка (SharedPreferences).
     *
     * Вызывается только после успешной фиксации транзакции: SharedPreferences не участвуют
     * в откате Room. Порядок «сначала БД, потом настройки» означает, что при сбое настроек
     * восстановленные партии и прогресс уже на месте, а не наоборот.
     */
    private fun applyOffDatabasePreferences(
        userId: Long,
        profile: UserProgressBackupCodec.ProfileSection
    ): Boolean {
        var changed = false
        sessionRepository?.let { sr ->
            if (sr.themeMode.value != profile.appThemeMode) {
                changed = true
            }
            sr.setThemeMode(profile.appThemeMode)
        }
        profile.chessUsernameHint?.let { hint ->
            if (sessionRepository?.getChessUsernameHint(userId).isNullOrBlank()) {
                sessionRepository?.saveChessUsernameHint(userId, hint)
                changed = true
            }
        }
        profile.engineSettings?.let { settings ->
            val validated = settings.validate()
            engineSettingsRepository?.let { repo ->
                if (repo.getSettings() != validated) {
                    changed = true
                }
                repo.saveSettings(validated)
            }
        }
        return changed
    }

    private suspend fun applySnapshot(
        input: Input,
        snapshot: UserProgressBackupCodec.BackupSnapshot
    ): Result {
        val user = userRepository.findById(input.userId)
            ?: return Result.Error("Пользователь не найден")
        val userId = user.id
            ?: return Result.Error("Некорректный идентификатор пользователя")

        // Гарантируем наличие встроенного банка задач для точного сопоставления попыток по CURATED_ID / FEN
        CuratedExerciseBank.ensureSeeded(exerciseRepository)

        // 1. Обновление профиля и настроек (не затрагивая email и passwordHash)
        val profile = snapshot.profile
        val shouldAdoptBackupRating = snapshot.attempts.isNotEmpty() &&
            (exerciseRepository.getAttemptsByUserId(userId).isEmpty() || profile.rating > user.rating)
        val mergedRating = if (shouldAdoptBackupRating) {
            profile.rating.coerceIn(100, 3500)
        } else {
            user.rating
        }

        val dbPreferencesChanged = input.restorePreferences &&
            (user.isSoundEnabled != profile.isSoundEnabled || user.preferredTheme != profile.preferredBoardTheme)

        val updatedUser = if (input.restorePreferences || mergedRating != user.rating) {
            val next = user.copy(
                rating = mergedRating,
                skillLevel = SkillLevel.fromRating(mergedRating),
                isSoundEnabled = if (input.restorePreferences) profile.isSoundEnabled else user.isSoundEnabled,
                preferredTheme = if (input.restorePreferences) {
                    profile.preferredBoardTheme
                } else {
                    user.preferredTheme
                }
            )
            userRepository.update(next)
            next
        } else {
            user
        }

        // 2. Восстановление партий, ходов и ошибок (с дедупликацией по отпечатку PGN)
        val existingGames = gameRepository.findByUserId(userId)
        val existingByKey = LinkedHashMap<String, Game>(existingGames.size)
        for (g in existingGames) {
            existingByKey.putIfAbsent(gameIdentityKey(g.pgnData, g.playerColor, g.playedAt), g)
        }

        val resolvedGameIdByIndex = HashMap<Int, Long>(snapshot.games.size)
        var gamesImported = 0
        var gamesUpgraded = 0
        var gamesSkipped = 0

        snapshot.games.forEachIndexed { idx, entry ->
            val key = gameIdentityKey(entry.pgnData, entry.playerColor, entry.playedAt)
            val existing = existingByKey[key]
            val normalizedStatus = if (entry.analysisStatus == AnalysisStatus.IN_PROGRESS) {
                AnalysisStatus.PENDING
            } else {
                entry.analysisStatus
            }
            if (existing == null) {
                val saved = gameRepository.save(
                    Game(
                        userId = userId,
                        pgnData = entry.pgnData,
                        playerColor = entry.playerColor,
                        timeControl = entry.timeControl,
                        playedAt = entry.playedAt,
                        analysisStatus = normalizedStatus,
                        analyzedAt = entry.analyzedAt,
                        accuracy = entry.accuracy,
                        averageEvaluationLoss = entry.averageEvaluationLoss,
                        totalMistakes = entry.totalMistakes,
                        blundersCount = entry.blundersCount,
                        mistakesCount = entry.mistakesCount,
                        inaccuraciesCount = entry.inaccuraciesCount,
                        evaluationsJson = entry.evaluationsJson
                    )
                )
                val savedId = saved.id
                if (savedId != null) {
                    resolvedGameIdByIndex[idx] = savedId
                    saveGameAnalysisArtifacts(savedId, entry)
                }
                existingByKey[key] = saved
                gamesImported++
            } else {
                val existingId = existing.id
                if (existingId != null) {
                    resolvedGameIdByIndex[idx] = existingId
                }
                // Если локальная партия ещё не была проанализирована, а в копии есть готовый разбор — переносим его
                if (existingId != null && !existing.isAnalyzed() && entry.analysisStatus == AnalysisStatus.COMPLETED) {
                    val upgraded = existing.copy(
                        analysisStatus = AnalysisStatus.COMPLETED,
                        analyzedAt = entry.analyzedAt,
                        accuracy = entry.accuracy,
                        averageEvaluationLoss = entry.averageEvaluationLoss,
                        totalMistakes = entry.totalMistakes,
                        blundersCount = entry.blundersCount,
                        mistakesCount = entry.mistakesCount,
                        inaccuraciesCount = entry.inaccuraciesCount,
                        evaluationsJson = entry.evaluationsJson
                    )
                    gameRepository.update(upgraded)
                    analyzedMoveRepository.deleteByGameId(existingId)
                    mistakeRepository.deleteByGameId(existingId)
                    saveGameAnalysisArtifacts(existingId, entry)
                    existingByKey[key] = upgraded
                    gamesUpgraded++
                } else {
                    gamesSkipped++
                }
            }
        }

        // 3. Восстановление персональных тактических задач (с перепривязкой sourceGameId)
        val resolvedPersonalExerciseIdByIndex = HashMap<Int, Long>(snapshot.personalExercises.size)
        var exercisesImported = 0

        snapshot.personalExercises.forEachIndexed { idx, exEntry ->
            val mappedGameId = exEntry.sourceGameIndex?.let { resolvedGameIdByIndex[it] }
            val existingPersonal = (if (mappedGameId != null && exEntry.sourceMoveNumber != null) {
                exerciseRepository.findBySourceGameAndMove(mappedGameId, exEntry.sourceMoveNumber)
            } else {
                null
            }) ?: exerciseRepository.findUserExerciseByFen(userId, exEntry.fenPosition)

            if (existingPersonal?.id != null) {
                resolvedPersonalExerciseIdByIndex[idx] = existingPersonal.id
            } else if (mappedGameId != null) {
                // Персональная задача в Room изолируется по принадлежности sourceGameId пользователю
                val savedEx = exerciseRepository.saveExercise(
                    Exercise(
                        fenPosition = exEntry.fenPosition,
                        solutionMoves = exEntry.solutionMoves,
                        tacticalPattern = exEntry.tacticalPattern,
                        patternClusterId = exEntry.patternClusterId,
                        difficultyRating = exEntry.difficultyRating,
                        patternFeatures = exEntry.patternFeatures,
                        theme = exEntry.theme,
                        rating = exEntry.rating,
                        createdFrom = exEntry.createdFrom,
                        sourceGameId = mappedGameId,
                        sourceMoveNumber = exEntry.sourceMoveNumber,
                        createdAt = exEntry.createdAt,
                        timesShown = exEntry.timesShown,
                        timesSolved = exEntry.timesSolved,
                        successRate = exEntry.successRate
                    )
                )
                savedEx.id?.let { resolvedPersonalExerciseIdByIndex[idx] = it }
                exercisesImported++
            }
        }

        // 4. Восстановление истории попыток SM-2/Лейтнера (без дублирования)
        val accessible = exerciseRepository.findAccessibleByUserId(userId)
        val exerciseIdByCurated = HashMap<String, Long>()
        val exerciseIdByFen = HashMap<String, Long>()
        for (ex in accessible) {
            val exId = ex.id ?: continue
            ExerciseMetadata.extractCuratedId(ex)?.let { cid -> exerciseIdByCurated[cid] = exId }
            exerciseIdByFen.putIfAbsent(ex.fenPosition.trim(), exId)
        }

        val existingSignatures = exerciseRepository.getAttemptsByUserId(userId)
            .mapTo(HashSet()) { a ->
                AttemptSignature(
                    exerciseId = a.exerciseId,
                    attemptedAt = a.attemptedAt,
                    solved = a.solved,
                    timeSpentSeconds = a.timeSpentSeconds,
                    movesMade = a.movesMade
                )
            }

        var attemptsImported = 0
        var attemptsSkipped = 0

        for (aEntry in snapshot.attempts) {
            val targetExId = aEntry.personalExerciseIndex?.let { resolvedPersonalExerciseIdByIndex[it] }
                ?: aEntry.curatedId?.let { exerciseIdByCurated[it] }
                ?: aEntry.exerciseFen?.let { exerciseIdByFen[it.trim()] }
                ?: continue

            val sig = AttemptSignature(
                exerciseId = targetExId,
                attemptedAt = aEntry.attemptedAt,
                solved = aEntry.solved,
                timeSpentSeconds = aEntry.timeSpentSeconds,
                movesMade = aEntry.movesMade
            )
            if (!existingSignatures.add(sig)) {
                attemptsSkipped++
                continue
            }

            exerciseRepository.saveAttempt(
                ExerciseAttempt(
                    userId = userId,
                    exerciseId = targetExId,
                    solved = aEntry.solved,
                    timeSpentSeconds = aEntry.timeSpentSeconds,
                    movesMade = aEntry.movesMade,
                    attemptedAt = aEntry.attemptedAt
                )
            )
            attemptsImported++
        }

        return Result.Success(
            ImportSummary(
                updatedUser = updatedUser,
                gamesImported = gamesImported,
                gamesAnalysisUpgraded = gamesUpgraded,
                gamesSkippedDuplicate = gamesSkipped,
                exercisesImported = exercisesImported,
                attemptsImported = attemptsImported,
                attemptsSkippedDuplicate = attemptsSkipped,
                preferencesUpdated = dbPreferencesChanged
            )
        )
    }

    private suspend fun saveGameAnalysisArtifacts(gameId: Long, entry: GameEntry) {
        if (entry.analyzedMoves.isNotEmpty()) {
            analyzedMoveRepository.saveAll(
                entry.analyzedMoves.map { m ->
                    AnalyzedMove(
                        gameId = gameId,
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
            )
        }
        if (entry.mistakes.isNotEmpty()) {
            mistakeRepository.saveAll(
                entry.mistakes.map { mk ->
                    Mistake(
                        gameId = gameId,
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
            )
        }
    }

    private fun gameIdentityKey(pgnData: String, color: ChessColor, playedAt: Long): String {
        val parsed = PgnParser.parseAndValidate(pgnData)
        if (parsed is PgnParser.ParseResult.Success) {
            return "fp:${parsed.game.fingerprint}:${color.name}"
        }
        val compact = pgnData.trim().replace(Regex("""\s+"""), " ").lowercase(Locale.ROOT)
        return "raw:${color.name}:$playedAt:${UserProgressBackupCodec.sha256Hex(compact)}"
    }
}
