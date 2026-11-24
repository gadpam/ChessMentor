// domain/usecase/GenerateExercisesFromMistakesUseCase.kt
package com.example.chessmentor.domain.usecase

import android.util.Log
import com.example.chessmentor.data.engine.ChessEngine
import com.example.chessmentor.domain.analysis.SemanticMistakeAnalyzer
import com.example.chessmentor.domain.analysis.TacticalMotif
import com.example.chessmentor.domain.clustering.ExerciseClusterer
import com.example.chessmentor.domain.engine.EngineAnalysisProfile
import com.example.chessmentor.domain.entity.AnalyzedMove
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Exercise
import com.example.chessmentor.domain.entity.ExerciseSource
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.entity.Mistake
import com.example.chessmentor.domain.entity.MistakeType
import com.example.chessmentor.domain.entity.TacticalPattern
import com.example.chessmentor.domain.entity.TacticalTheme
import com.example.chessmentor.domain.repository.AnalyzedMoveRepository
import com.example.chessmentor.domain.repository.ExerciseRepository
import com.example.chessmentor.domain.repository.GameRepository
import com.example.chessmentor.domain.repository.MistakeRepository
import com.example.chessmentor.domain.training.ExerciseMetadata
import com.example.chessmentor.domain.training.PuzzleKind
import com.example.chessmentor.domain.training.PuzzleQualityEvaluator
import com.example.chessmentor.domain.training.SolutionReviewHelper
import com.example.chessmentor.domain.training.TrainingRatingCalculator
import com.example.chessmentor.domain.util.ChessMoveUtils
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Use Case: Генерация тренировочных задач с кластеризацией из ошибок пользователя.
 *
 * Отбирает только учебно ценные позиции (исключая безнадёжно проигранные эндшпили
 * и мелкие позиционные неточности), сохраняет предшествующий ход соперника для подсветки,
 * гарантирует нечётную длину решения (последний ход за игроком) и изолирует дедупликацию по userId.
 */
class GenerateExercisesFromMistakesUseCase(
    private val mistakeRepository: MistakeRepository,
    private val analyzedMoveRepository: AnalyzedMoveRepository,
    private val exerciseRepository: ExerciseRepository,
    private val gameRepository: GameRepository,
    private val chessEngine: ChessEngine
) {
    companion object {
        private const val TAG = "GenerateExercisesUC"
        private const val SOLUTION_DEPTH = EngineAnalysisProfile.PUZZLE_GENERATION_DEPTH
        private const val CLARITY_CHECK_DEPTH = EngineAnalysisProfile.CLARITY_CHECK_DEPTH
        private const val MAX_SOLUTION_PLIES = PuzzleQualityEvaluator.MAX_QUIESCENCE_PLIES

        /** Позиция уже безнадёжна для игрока до хода (≤ -5.5 пешки) — не подходит для задачи. */
        const val HOPELESS_EVAL_THRESHOLD_CP = PuzzleQualityEvaluator.HOPELESS_EVAL_THRESHOLD_CP

        /** Позиция уже абсолютно выиграна (≥ +7.0 пешек) — берём только грубые зевки ≥ 400 сП. */
        const val ALREADY_CRUSHING_EVAL_CP = PuzzleQualityEvaluator.ALREADY_CRUSHING_EVAL_CP
        const val MIN_LOSS_IN_CRUSHING_POS_CP = PuzzleQualityEvaluator.MIN_LOSS_IN_CRUSHING_POS_CP

        /**
         * Чистая проверка пригодности ошибки для превращения в тренировочную задачу:
         * пропускает как чёткую выигрывающую тактику, так и задачи на единственную защиту/спасение,
         * отсекая безнадёжные и «мутные» позиционные неточности.
         */
        fun isMistakeSuitableForExercise(
            mistake: Mistake,
            analyzedMove: AnalyzedMove?
        ): Boolean = PuzzleQualityEvaluator.classifyMistake(mistake, analyzedMove) != PuzzleKind.REJECTED
    }

    private val semanticAnalyzer = SemanticMistakeAnalyzer(chessEngine)

    data class Input(
        val userId: Long,
        val maxExercises: Int = 50
    )

    sealed class Result {
        data class Success(
            val generatedCount: Int,
            val exercises: List<Exercise>
        ) : Result()

        data class Error(val message: String) : Result()
    }

    private data class GameMoveContext(
        val byMoveAndColor: Map<Pair<Int, ChessColor>, AnalyzedMove>,
        val prevMoveUciByIndex: Map<Int, String>,
        val preFenByIndex: Map<Int, String>
    )

    suspend fun execute(input: Input): Result = withContext(Dispatchers.IO) {
        if (input.userId <= 0L || input.maxExercises <= 0) {
            return@withContext Result.Success(0, emptyList())
        }

        try {
            val games = gameRepository.findByUserId(input.userId)
            val gamesById = games.mapNotNull { g -> g.id?.let { it to g } }.toMap()
            if (gamesById.isEmpty()) {
                return@withContext Result.Success(0, emptyList())
            }

            // Приоритет: сначала зевки и серьёзные ошибки, затем ощутимые неточности
            val mistakes = mistakeRepository.findByGameIds(gamesById.keys.toList())
                .filter { !it.fenBefore.isNullOrBlank() }
                .sortedWith(
                    compareByDescending<Mistake> { it.getSeverity() }
                        .thenByDescending { it.evaluationLoss }
                )

            if (mistakes.isEmpty()) {
                return@withContext Result.Success(0, emptyList())
            }

            val contextCache = mutableMapOf<Long, GameMoveContext>()
            val engineReady = runCatching { chessEngine.init() }.getOrDefault(false)
            val created = mutableListOf<Exercise>()

            for (mistake in mistakes) {
                coroutineContext.ensureActive()
                if (created.size >= input.maxExercises) break

                val fen = mistake.fenBefore?.trim().orEmpty()
                if (fen.isEmpty()) continue

                val gameContext = contextCache.getOrPut(mistake.gameId) {
                    loadGameMoveContext(gamesById[mistake.gameId], mistake.gameId)
                }
                val analyzedMove = gameContext.byMoveAndColor[mistake.moveNumber to mistake.color]
                val puzzleKind = PuzzleQualityEvaluator.classifyMistake(mistake, analyzedMove)
                if (puzzleKind == PuzzleKind.REJECTED) {
                    continue
                }

                // Изолированная по пользователю проверка дубликатов
                if (exerciseRepository.findBySourceGameAndMove(mistake.gameId, mistake.moveNumber) != null) {
                    continue
                }
                if (exerciseRepository.findUserExerciseByFen(input.userId, fen) != null) {
                    continue
                }

                val prevMoveUci = analyzedMove?.let { gameContext.prevMoveUciByIndex[it.moveIndex] }
                val preFen = analyzedMove?.let { gameContext.preFenByIndex[it.moveIndex] }
                val exercise = buildExerciseFromMistake(
                    mistake = mistake,
                    fen = fen,
                    preFen = preFen,
                    prevMoveUci = prevMoveUci,
                    puzzleKind = puzzleKind,
                    engineReady = engineReady
                ) ?: continue
                val saved = exerciseRepository.saveExercise(exercise)
                created.add(saved)
            }

            if (created.isNotEmpty()) {
                exerciseRepository.deleteDuplicatesForUser(input.userId)
                Log.i(TAG, "Generated ${created.size} exercises for user ${input.userId}")
            }

            Result.Success(created.size, created)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate exercises", e)
            Result.Error("Ошибка генерации упражнений: ${e.message}")
        }
    }

    /**
     * Прицельная генерация тренировочных задач только для одной выбранной партии [gameId]
     * (используется мостом «Разбор партии → Тренировка»).
     *
     * Проверяет принадлежность партии пользователю [userId]. Если передан [focusMoveNumber],
     * эта конкретная ошибка обрабатывается первой и допускается даже при пограничной потере оценки,
     * чтобы переход с карточки ошибки в тренажёр срабатывал надёжно.
     */
    suspend fun executeForGame(
        userId: Long,
        gameId: Long,
        focusMoveNumber: Int? = null,
        maxExercises: Int = 12
    ): Result = withContext(Dispatchers.IO) {
        if (userId <= 0L || gameId <= 0L || maxExercises <= 0) {
            return@withContext Result.Success(0, emptyList())
        }

        try {
            val game = gameRepository.findById(gameId)
                ?: return@withContext Result.Error("Партия не найдена")
            if (game.userId != userId) {
                return@withContext Result.Error("Нет доступа к выбранной партии")
            }

            val allMistakes = mistakeRepository.findByGameIds(listOf(gameId))
                .filter {
                    it.color == game.playerColor &&
                        !it.fenBefore.isNullOrBlank() &&
                        it.bestMove.isNotBlank() &&
                        it.bestMove != it.userMove
                }
                .sortedWith(
                    compareByDescending<Mistake> { focusMoveNumber != null && it.moveNumber == focusMoveNumber }
                        .thenByDescending { it.getSeverity() }
                        .thenByDescending { it.evaluationLoss }
                )

            if (allMistakes.isEmpty()) {
                return@withContext Result.Success(0, emptyList())
            }

            val gameContext = loadGameMoveContext(game, gameId)
            val engineReady = runCatching { chessEngine.init() }.getOrDefault(false)
            val created = mutableListOf<Exercise>()

            suspend fun tryCreateFromMistake(mistake: Mistake, relaxFilter: Boolean): Exercise? {
                val fen = mistake.fenBefore?.trim().orEmpty()
                if (fen.isEmpty()) return null

                // Если задача по этому ходу уже создана — повторно не генерируем
                if (exerciseRepository.findBySourceGameAndMove(gameId, mistake.moveNumber) != null) {
                    return null
                }
                val existingByFen = exerciseRepository.findUserExerciseByFen(userId, fen)
                if (existingByFen != null && !relaxFilter) {
                    return null
                }

                val analyzedMove = gameContext.byMoveAndColor[mistake.moveNumber to mistake.color]
                val rawKind = PuzzleQualityEvaluator.classifyMistake(mistake, analyzedMove)
                val effectiveKind = when {
                    rawKind != PuzzleKind.REJECTED -> rawKind
                    relaxFilter -> {
                        val playerEvalBefore = analyzedMove?.let {
                            if (it.color == ChessColor.WHITE) it.evalBefore else -it.evalBefore
                        } ?: 0
                        if (playerEvalBefore <= 40 && mistake.evaluationLoss >= 120) {
                            PuzzleKind.DEFENSIVE_RESOURCE
                        } else {
                            PuzzleKind.WINNING_TACTIC
                        }
                    }
                    else -> return null
                }

                val prevMoveUci = analyzedMove?.let { gameContext.prevMoveUciByIndex[it.moveIndex] }
                val preFen = analyzedMove?.let { gameContext.preFenByIndex[it.moveIndex] }
                val exercise = buildExerciseFromMistake(
                    mistake = mistake,
                    fen = fen,
                    preFen = preFen,
                    prevMoveUci = prevMoveUci,
                    puzzleKind = effectiveKind,
                    engineReady = engineReady,
                    relaxAmbiguityCheck = relaxFilter
                ) ?: return null
                return exerciseRepository.saveExercise(exercise)
            }

            // 1. Строгий проход (с явным приоритетом и мягким допуском для выбранного хода focusMoveNumber)
            for (mistake in allMistakes) {
                coroutineContext.ensureActive()
                if (created.size >= maxExercises) break
                val isExplicitFocus = focusMoveNumber != null && mistake.moveNumber == focusMoveNumber
                val saved = tryCreateFromMistake(mistake, relaxFilter = isExplicitFocus)
                if (saved != null) created.add(saved)
            }

            // 2. Если в партии пока нет ни одной задачи, но есть ощутимые ошибки (>= 90 сП),
            // создаём прицельные упражнения по тому же правилу, что и счётчик на экране итогов.
            val existingForGame = exerciseRepository.findByUserId(userId)
                .any { it.sourceGameId == gameId }
            if (!existingForGame && created.isEmpty()) {
                val fallbackCandidates = PuzzleQualityEvaluator.selectTrainableMistakes(
                    mistakes = allMistakes,
                    analyzedByMoveAndColor = gameContext.byMoveAndColor,
                    playerColor = game.playerColor,
                    maxFallback = maxExercises.coerceAtMost(5)
                )
                for (mistake in fallbackCandidates) {
                    coroutineContext.ensureActive()
                    val saved = tryCreateFromMistake(mistake, relaxFilter = true)
                    if (saved != null) created.add(saved)
                }
            }

            Result.Success(created.size, created)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate exercises for game $gameId", e)
            Result.Error("Ошибка генерации упражнений партии: ${e.message}")
        }
    }

    /**
     * Загружает проанализированные ходы партии и за один проход вычисляет
     * предшествующий ход соперника (в UCI) для каждого полухода.
     */
    private suspend fun loadGameMoveContext(game: Game?, gameId: Long): GameMoveContext {
        val moves = runCatching { analyzedMoveRepository.findByGameId(gameId) }
            .getOrDefault(emptyList())
            .sortedBy { it.moveIndex }

        if (moves.isEmpty()) {
            return GameMoveContext(emptyMap(), emptyMap(), emptyMap())
        }

        val byMoveAndColor = moves.associateBy { it.moveNumber to it.color }
        val prevUciMap = mutableMapOf<Int, String>()
        val preFenMap = mutableMapOf<Int, String>()

        runCatching {
            val board = Board()
            val customStartFen = game?.pgnData
                ?.let { runCatching { ChessMoveUtils.normalizePgn(it).startFen }.getOrNull() }
                ?.takeIf { it.isNotBlank() }
            if (customStartFen != null) {
                board.loadFromFen(customStartFen)
            }

            var lastUci: String? = null
            var lastPreFen: String? = null
            var expectedIndex = 0
            for (am in moves) {
                if (am.moveIndex != expectedIndex) break
                if (lastUci != null && lastPreFen != null) {
                    prevUciMap[am.moveIndex] = lastUci
                    preFenMap[am.moveIndex] = lastPreFen
                }
                val fenBeforeThisMove = board.fen
                val uci = ChessMoveUtils.sanOrUciToUci(am.san, fenBeforeThisMove) ?: break
                if (!ChessMoveUtils.applyUciMove(board, uci)) break
                lastPreFen = fenBeforeThisMove
                lastUci = uci
                expectedIndex++
            }
        }

        return GameMoveContext(byMoveAndColor, prevUciMap, preFenMap)
    }

    private suspend fun buildExerciseFromMistake(
        mistake: Mistake,
        fen: String,
        preFen: String?,
        prevMoveUci: String?,
        puzzleKind: PuzzleKind,
        engineReady: Boolean,
        relaxAmbiguityCheck: Boolean = false
    ): Exercise? {
        val board = try {
            Board().apply { loadFromFen(fen) }
        } catch (e: Exception) {
            Log.w(TAG, "Skipping invalid FEN in mistake ${mistake.id}")
            return null
        }

        // Проверяем, что в позиции вообще есть легальные ходы
        if (runCatching { board.legalMoves() }.getOrDefault(emptyList()).size <= 1 && !board.isKingAttacked) {
            return null
        }

        val solutionMoves = resolveSolutionMoves(
            board = board,
            fen = fen,
            fallbackBestMove = mistake.bestMove,
            puzzleKind = puzzleKind,
            engineReady = engineReady,
            relaxAmbiguityCheck = relaxAmbiguityCheck
        )
        if (solutionMoves.isEmpty()) return null

        val firstMoveUci = solutionMoves.first()
        val firstMove = ChessMoveUtils.parseUciMove(board, firstMoveUci) ?: return null
        val movingPiece = board.getPiece(firstMove.from)

        val motif = semanticAnalyzer.detectMotif(board, firstMoveUci)
        val isDefensive = puzzleKind == PuzzleKind.DEFENSIVE_RESOURCE
        val pattern = classifyPattern(board, firstMove, movingPiece, solutionMoves, motif, mistake, isDefensive)
        val theme = if (isDefensive && pattern != TacticalPattern.BACK_RANK_MATE && pattern != TacticalPattern.SMOTHERED_MATE) {
            TacticalTheme.KING_SAFETY
        } else {
            mapPatternToTheme(pattern)
        }

        val difficulty = ExerciseClusterer.calculateDifficulty(
            board = board,
            pattern = pattern,
            solutionMoves = solutionMoves,
            materialGain = mistake.evaluationLoss.coerceAtMost(1200)
        )
        val clusterId = ExerciseClusterer.generateClusterId(
            board = board,
            pattern = pattern,
            solutionMove = firstMoveUci,
            difficultyRating = difficulty
        )
        val baseFeatures = ExerciseClusterer.extractFeatures(
            board = board,
            pattern = pattern,
            solutionMove = firstMoveUci
        )
        val features = buildList {
            addAll(baseFeatures)
            if (isDefensive) {
                add(ExerciseMetadata.defensiveGoalTag())
            }
            if (!prevMoveUci.isNullOrBlank() && ChessMoveUtils.isUciMove(prevMoveUci)) {
                add(ExerciseMetadata.prevMoveTag(prevMoveUci))
                if (!preFen.isNullOrBlank() &&
                    SolutionReviewHelper.validateTriggerTransition(preFen, prevMoveUci, fen)
                ) {
                    add(ExerciseMetadata.preFenTag(preFen))
                }
            }
        }

        val estimatedElo = TrainingRatingCalculator.difficultyToElo(difficulty)

        return Exercise(
            fenPosition = fen,
            solutionMoves = solutionMoves,
            tacticalPattern = pattern,
            patternClusterId = clusterId,
            difficultyRating = difficulty,
            patternFeatures = features,
            theme = theme,
            rating = estimatedElo,
            createdFrom = ExerciseSource.MISTAKE,
            sourceGameId = mistake.gameId,
            sourceMoveNumber = mistake.moveNumber
        )
    }

    private suspend fun resolveSolutionMoves(
        board: Board,
        fen: String,
        fallbackBestMove: String,
        puzzleKind: PuzzleKind,
        engineReady: Boolean,
        relaxAmbiguityCheck: Boolean = false
    ): List<String> {
        if (engineReady) {
            try {
                val line = chessEngine.getBestMoveWithLine(fen, SOLUTION_DEPTH)
                if (line != null && line.principalVariation.isNotEmpty()) {
                    val validated = validateUciSequence(board, line.principalVariation.take(MAX_SOLUTION_PLIES))
                    if (validated.isNotEmpty()) {
                        if (!relaxAmbiguityCheck) {
                            // Фильтр тактической чёткости: отбрасываем «угадайки», где 2+ других хода не хуже лучшего
                            val isSharp = PuzzleQualityEvaluator.hasClearTacticalSolution(
                                board = board,
                                bestMoveUci = validated.first(),
                                bestScoreSideToMove = line.score,
                                puzzleKind = puzzleKind,
                                engine = chessEngine,
                                checkDepth = CLARITY_CHECK_DEPTH
                            )
                            if (!isSharp) {
                                Log.d(TAG, "Skipping ambiguous position (multiple equal moves): $fen")
                                return emptyList()
                            }
                        }

                        // Обрезаем PV в точке успокоения позиции (после завершения разменов/шахов)
                        val quiescent = PuzzleQualityEvaluator.trimPvAtQuiescence(
                            startFen = fen,
                            rawUciMoves = validated,
                            maxPlies = MAX_SOLUTION_PLIES
                        )
                        if (quiescent.isNotEmpty()) {
                            return quiescent
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Engine PV failed, falling back to saved bestMove", e)
            }
        }

        val fallbackUci = ChessMoveUtils.sanOrUciToUci(fallbackBestMove, fen)
        return if (fallbackUci != null) listOf(fallbackUci) else emptyList()
    }

    private fun validateUciSequence(startBoard: Board, moves: List<String>): List<String> {
        val sim = Board().apply { loadFromFen(startBoard.fen) }
        val result = mutableListOf<String>()
        for (raw in moves) {
            val move = ChessMoveUtils.parseUciMove(sim, raw) ?: break
            if (!sim.doMove(move)) break
            result.add(ChessMoveUtils.moveToUci(move))
        }
        return result
    }

    private fun classifyPattern(
        board: Board,
        firstMove: Move,
        movingPiece: Piece,
        solutionMoves: List<String>,
        motif: TacticalMotif?,
        mistake: Mistake,
        isDefensive: Boolean
    ): TacticalPattern {
        // Если решение завершается форсированным матом — классифицируем как матовый паттерн
        detectForcedMatePattern(board, solutionMoves)?.let { return it }

        // Для защитных задач выделяем пат, контрудар (цвишенцуг) или точную защиту
        if (isDefensive) {
            if (endsInStalemate(board, solutionMoves)) {
                return TacticalPattern.STALEMATE_TRICK
            }
            return if (PuzzleQualityEvaluator.isCounterAttackingDefense(board, solutionMoves)) {
                TacticalPattern.COUNTER_ATTACK
            } else {
                TacticalPattern.DEFENSIVE_MOVE
            }
        }

        if (firstMove.promotion != Piece.NONE) {
            return TacticalPattern.PAWN_BREAKTHROUGH
        }

        if (motif != null) {
            if (motif == TacticalMotif.FORK) {
                return when (movingPiece.pieceType) {
                    PieceType.KNIGHT -> TacticalPattern.FORK_KNIGHT
                    PieceType.BISHOP -> TacticalPattern.FORK_BISHOP
                    PieceType.ROOK -> TacticalPattern.FORK_ROOK
                    PieceType.QUEEN -> TacticalPattern.FORK_QUEEN
                    PieceType.PAWN -> TacticalPattern.FORK_PAWN
                    else -> TacticalPattern.FORK_KNIGHT
                }
            }
            if (motif == TacticalMotif.PIN && createsPinToKing(board, firstMove, movingPiece)) {
                return TacticalPattern.PIN_ABSOLUTE
            }
            return TacticalPattern.fromMotif(motif)
        }

        return when {
            ChessEngine.isMateScore(mistake.evaluationLoss) || mistake.evaluationLoss > 50000 ->
                TacticalPattern.BACK_RANK_MATE
            board.isKingAttacked -> TacticalPattern.DEFENSIVE_MOVE
            mistake.moveNumber >= 38 -> TacticalPattern.ENDGAME_TECHNIQUE
            mistake.mistakeType == MistakeType.BLUNDER -> TacticalPattern.CALCULATION
            mistake.mistakeType == MistakeType.INACCURACY -> TacticalPattern.POSITIONAL
            else -> TacticalPattern.MIXED
        }
    }

    /**
     * Если серия ходов [solutionMoves] завершается матом, определяет конкретный матовый мотив.
     */
    private fun detectForcedMatePattern(board: Board, solutionMoves: List<String>): TacticalPattern? {
        if (solutionMoves.isEmpty()) return null
        val sim = Board().apply { loadFromFen(board.fen) }
        var lastMovedPieceType: PieceType? = null
        for (uci in solutionMoves) {
            val move = ChessMoveUtils.parseUciMove(sim, uci) ?: return null
            lastMovedPieceType = sim.getPiece(move.from).pieceType
            if (!sim.doMove(move)) return null
            if (sim.isMated) {
                return if (lastMovedPieceType == PieceType.KNIGHT) {
                    TacticalPattern.SMOTHERED_MATE
                } else {
                    TacticalPattern.BACK_RANK_MATE
                }
            }
        }
        return null
    }

    private fun endsInStalemate(board: Board, solutionMoves: List<String>): Boolean {
        if (solutionMoves.isEmpty()) return false
        val sim = Board().apply { loadFromFen(board.fen) }
        for (uci in solutionMoves) {
            val move = ChessMoveUtils.parseUciMove(sim, uci) ?: return false
            if (!sim.doMove(move)) return false
            if (sim.isStaleMate) return true
        }
        return false
    }

    /**
     * Проверяет, связывает ли дальнобойная фигура после [move] фигуру соперника именно с королём.
     */
    private fun createsPinToKing(board: Board, move: Move, movingPiece: Piece): Boolean {
        val pt = movingPiece.pieceType
        if (pt != PieceType.BISHOP && pt != PieceType.ROOK && pt != PieceType.QUEEN) return false
        val after = Board().apply { loadFromFen(board.fen) }
        if (!after.doMove(move)) return false

        val enemySide = movingPiece.pieceSide.flip()
        val directions = buildList {
            if (pt == PieceType.BISHOP || pt == PieceType.QUEEN) {
                addAll(listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1))
            }
            if (pt == PieceType.ROOK || pt == PieceType.QUEEN) {
                addAll(listOf(0 to -1, 0 to 1, -1 to 0, 1 to 0))
            }
        }

        val startFile = move.to.file.ordinal
        val startRank = move.to.rank.ordinal
        for ((df, dr) in directions) {
            var f = startFile + df
            var r = startRank + dr
            var seenEnemyPiece = false
            while (f in 0..7 && r in 0..7) {
                val p = after.getPiece(Square.squareAt(r * 8 + f))
                if (p != Piece.NONE) {
                    if (p.pieceSide != enemySide) break
                    if (!seenEnemyPiece) {
                        if (p.pieceType == PieceType.KING) break
                        seenEnemyPiece = true
                    } else {
                        if (p.pieceType == PieceType.KING) return true
                        break
                    }
                }
                f += df
                r += dr
            }
        }
        return false
    }

    private fun mapPatternToTheme(pattern: TacticalPattern): TacticalTheme {
        return when (pattern) {
            TacticalPattern.FORK_KNIGHT,
            TacticalPattern.FORK_BISHOP,
            TacticalPattern.FORK_ROOK,
            TacticalPattern.FORK_QUEEN,
            TacticalPattern.FORK_PAWN -> TacticalTheme.FORK

            TacticalPattern.PIN_ABSOLUTE,
            TacticalPattern.PIN_RELATIVE,
            TacticalPattern.SKEWER -> TacticalTheme.PIN

            TacticalPattern.DISCOVERED_ATTACK,
            TacticalPattern.DISCOVERED_CHECK,
            TacticalPattern.DOUBLE_CHECK -> TacticalTheme.DISCOVERED_ATTACK

            TacticalPattern.BACK_RANK_MATE,
            TacticalPattern.SMOTHERED_MATE,
            TacticalPattern.ANASTASIA_MATE,
            TacticalPattern.ARABIAN_MATE,
            TacticalPattern.BODEN_MATE -> TacticalTheme.MATE

            TacticalPattern.HANGING_PIECE,
            TacticalPattern.TRAPPED_PIECE,
            TacticalPattern.QUEEN_TRAP -> TacticalTheme.MATERIAL_LOSS

            TacticalPattern.DEFENSIVE_MOVE -> TacticalTheme.KING_SAFETY

            else -> TacticalTheme.TACTIC
        }
    }
}
