// domain/usecase/AnalyzeGameUseCase.kt
package com.example.chessmentor.domain.usecase

import android.util.Log
import com.example.chessmentor.data.engine.AnalysisLine
import com.example.chessmentor.data.engine.ChessEngine
import com.example.chessmentor.domain.analysis.GameMoveClassifier
import com.example.chessmentor.domain.analysis.MistakeAnalysis
import com.example.chessmentor.domain.analysis.MistakeCategory
import com.example.chessmentor.domain.analysis.SemanticMistakeAnalyzer
import com.example.chessmentor.domain.analysis.TacticalMotif
import com.example.chessmentor.domain.entity.AnalysisStatus
import com.example.chessmentor.domain.entity.AnalyzedMove
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.entity.Mistake
import com.example.chessmentor.domain.entity.MoveQuality
import com.example.chessmentor.domain.entity.PredefinedThemes
import com.example.chessmentor.domain.opening.BookMoveMatch
import com.example.chessmentor.domain.opening.OpeningDatabase
import com.example.chessmentor.domain.repository.AnalyzedMoveRepository
import com.example.chessmentor.domain.repository.EngineSettingsRepository
import com.example.chessmentor.domain.repository.GameRepository
import com.example.chessmentor.domain.repository.MistakeRepository
import com.example.chessmentor.domain.repository.UserRepository
import com.example.chessmentor.domain.util.ChessMoveUtils
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.move.Move
import com.github.bhlangonijr.chesslib.move.MoveList
import com.github.bhlangonijr.chesslib.pgn.PgnHolder
import java.io.File
import java.util.Collections
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AnalyzeGameUseCase(
    private val gameRepository: GameRepository,
    private val mistakeRepository: MistakeRepository,
    private val analyzedMoveRepository: AnalyzedMoveRepository,
    private val userRepository: UserRepository,
    private val chessEngine: ChessEngine,
    private val engineSettingsRepository: EngineSettingsRepository
) {
    companion object {
        private const val TAG = "AnalyzeGameUseCase"

        // Активные анализы в рамках текущего процесса (чтобы не блокировать партию навечно после краша)
        private val activeGameIds: MutableSet<Long> =
            Collections.synchronizedSet(mutableSetOf<Long>())

        private const val PV_PREVIEW_PLIES = 4
        private const val REFUTATION_PREVIEW_PLIES = 3
    }

    private val semanticAnalyzer = SemanticMistakeAnalyzer(chessEngine)

    data class Input(
        val gameId: Long,
        val forceReanalyze: Boolean = false
    )

    sealed class AnalysisProgress {
        object Starting : AnalysisProgress()
        data class InProgress(
            val currentMove: Int,
            val totalMoves: Int,
            val currentMoveNotation: String = ""
        ) : AnalysisProgress()
        data class Completed(val result: Result) : AnalysisProgress()
        data class Failed(val error: String) : AnalysisProgress()

        companion object {
            /**
             * Занят ли движок активным разбором.
             *
             * После [Completed] и [Failed] нативный поиск уже завершён, поэтому движок
             * считается свободным: иначе UI навсегда блокировал бы live-анализ и проверку
             * альтернативного хода, если диалог прогресса никто не закрыл.
             */
            fun isEngineBusy(progress: AnalysisProgress?): Boolean = when (progress) {
                null, is Completed, is Failed -> false
                is Starting, is InProgress -> true
            }
        }
    }

    sealed class Result {
        data class Success(
            val game: Game,
            val analyzedMoves: List<AnalyzedMove>,
            val mistakes: List<Mistake>,
            val evaluations: List<Int>
        ) : Result()
        data class Error(val message: String) : Result()
    }

    /**
     * Основной метод анализа партии с потоковым прогрессом.
     *
     * Анализирует ходы обеих сторон (игрока и соперника) через единую модель Win%
     * ([GameMoveClassifier]), но ошибки в таблице Mistake и персональные метрики Game
     * сохраняет строго по ходам самого игрока ([Game.playerColor]).
     */
    fun executeWithProgress(input: Input): Flow<AnalysisProgress> = flow {
        emit(AnalysisProgress.Starting)
        Log.d(TAG, "Starting analysis flow for game ${input.gameId}")

        val engineSettings = engineSettingsRepository.getSettings()
        Log.i(
            TAG,
            "Engine settings: depth=${engineSettings.depth}, " +
                "threads=${engineSettings.threads}, hash=${engineSettings.hashSizeMb}MB"
        )

        var gameInProgress: Game? = null
        var originalStatus = AnalysisStatus.PENDING
        var clearedOldArtifacts = false
        var acquiredSlot = false

        try {
            val game = gameRepository.findById(input.gameId)
            if (game == null || game.id == null) {
                emit(AnalysisProgress.Failed("Партия не найдена"))
                return@flow
            }
            val gameId = game.id

            if (game.analysisStatus == AnalysisStatus.COMPLETED && !input.forceReanalyze) {
                val savedMoves = analyzedMoveRepository.findByGameId(gameId)
                val savedMistakes = mistakeRepository.findByGameId(gameId)
                val savedEvaluations = game.getEvaluations()

                emit(
                    AnalysisProgress.Completed(
                        Result.Success(game, savedMoves, savedMistakes, savedEvaluations)
                    )
                )
                return@flow
            }

            if (!activeGameIds.add(gameId)) {
                emit(AnalysisProgress.Failed("Партия уже анализируется"))
                return@flow
            }
            acquiredSlot = true

            val user = userRepository.findById(game.userId)
            if (user == null) {
                emit(AnalysisProgress.Failed("Пользователь не найден"))
                return@flow
            }

            originalStatus = game.analysisStatus
            gameInProgress = game.startAnalysis()
            gameRepository.update(gameInProgress)

            val engineReady = withContext(Dispatchers.IO) {
                chessEngine.init()
            }

            if (!engineReady) {
                val failedGame = gameInProgress.failAnalysis()
                gameRepository.update(failedGame)
                gameInProgress = null
                emit(AnalysisProgress.Failed("Не удалось запустить шахматный движок (проверьте интернет для первой загрузки сети NNUE)"))
                return@flow
            }

            // Настраиваем движок и очищаем хеш-таблицу перед новой партией,
            // чтобы оценки от прошлых партий и задач не искажали расчёт.
            withContext(Dispatchers.IO) {
                chessEngine.setOption("Threads", engineSettings.threads.toString())
                chessEngine.setOption("Hash", engineSettings.hashSizeMb.toString())
                chessEngine.clearHash()
            }

            val progressChannel = Channel<AnalysisProgress.InProgress>(Channel.BUFFERED)
            var analysisResult: AnalysisData? = null
            var analysisError: Exception? = null

            coroutineScope {
                val analysisJob = launch(Dispatchers.IO) {
                    try {
                        analysisResult = analyzeAllMoves(
                            gameId = gameId,
                            pgn = game.pgnData,
                            analysisDepth = engineSettings.depth
                        ) { current, total, moveNotation ->
                            progressChannel.trySend(
                                AnalysisProgress.InProgress(current, total, moveNotation)
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        analysisError = e
                    } finally {
                        progressChannel.close()
                    }
                }

                for (progress in progressChannel) {
                    emit(progress)
                }

                analysisJob.join()
            }

            analysisError?.let { throw it }

            val data = analysisResult ?: throw IllegalStateException("Анализ не вернул результат")

            // Очищаем возможные старые записи при повторном анализе
            clearedOldArtifacts = true
            analyzedMoveRepository.deleteByGameId(gameId)
            mistakeRepository.deleteByGameId(gameId)

            val savedMoves = analyzedMoveRepository.saveAll(data.analyzedMoves)

            // Ошибки и персональная статистика Game считаются СТРОГО по ходам игрока
            val playerMistakes = savedMoves
                .filter { it.color == game.playerColor && it.isMistake() }
                .mapNotNull { move ->
                    val fenBefore = data.fenPositions[move.moveIndex].orEmpty()
                    val bestMoveUci = data.bestMovesUci[move.moveIndex].orEmpty()
                    val semantic = data.semanticByMoveIndex[move.moveIndex]
                    move.toMistake(
                        themeId = detectThemeId(move, fenBefore, bestMoveUci, semantic),
                        fenBefore = fenBefore
                    )
                }

            val savedMistakes = mistakeRepository.saveAll(playerMistakes)

            val playerSummary = GameMoveClassifier.summarizeSide(savedMoves, game.playerColor)
            val opponentSummary = GameMoveClassifier.summarizeSide(savedMoves, game.playerColor.opposite())

            val blunders = playerSummary.blunders
            val mistakesCount = playerSummary.mistakes + playerSummary.missedWins
            val inaccuracies = playerSummary.inaccuracies
            val accuracy = playerSummary.accuracy
            val avgLoss = playerSummary.averageCpLoss

            val analyzedGame = gameInProgress.completeAnalysis(
                accuracy = accuracy,
                avgLoss = avgLoss,
                blunders = blunders,
                mistakes = mistakesCount,
                inaccuracies = inaccuracies,
                evaluations = data.evaluations
            )

            val finalGame = gameRepository.update(analyzedGame)
            gameInProgress = null

            Log.i(
                TAG,
                "Final: playerAcc=${String.format(Locale.US, "%.1f", accuracy)}% " +
                    "(acpl=$avgLoss, B=$blunders, M=$mistakesCount, I=$inaccuracies), " +
                    "oppAcc=${String.format(Locale.US, "%.1f", opponentSummary.accuracy)}%"
            )

            emit(
                AnalysisProgress.Completed(
                    Result.Success(finalGame, savedMoves, savedMistakes, data.evaluations)
                )
            )
        } catch (e: CancellationException) {
            // При отмене возвращаем партию в PENDING (или в COMPLETED, если старый разбор ещё цел)
            gameInProgress?.let { inProgress ->
                val restoreStatus = if (!clearedOldArtifacts && originalStatus == AnalysisStatus.COMPLETED) {
                    AnalysisStatus.COMPLETED
                } else {
                    AnalysisStatus.PENDING
                }
                withContext(NonCancellable) {
                    runCatching {
                        gameRepository.update(inProgress.copy(analysisStatus = restoreStatus))
                    }
                }
            }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Analysis failed", e)
            gameInProgress?.let { inProgress ->
                withContext(NonCancellable) {
                    runCatching { gameRepository.update(inProgress.failAnalysis()) }
                }
            }
            emit(AnalysisProgress.Failed("Ошибка анализа: ${e.message ?: "неизвестная ошибка"}"))
        } finally {
            if (acquiredSlot) {
                activeGameIds.remove(input.gameId)
            }
        }
    }

    /**
     * Синхронный метод анализа
     */
    suspend fun execute(input: Input): Result = withContext(Dispatchers.Default) {
        var lastResult: Result = Result.Error("Анализ не завершён")

        executeWithProgress(input).collect { progress ->
            when (progress) {
                is AnalysisProgress.Completed -> lastResult = progress.result
                is AnalysisProgress.Failed -> lastResult = Result.Error(progress.error)
                else -> {}
            }
        }

        lastResult
    }

    /** Сбой движка во время разбора: результат партии недостоверен и не сохраняется. */
    class EngineAnalysisException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    private data class AnalysisData(
        val analyzedMoves: List<AnalyzedMove>,
        val evaluations: List<Int>,
        val fenPositions: Map<Int, String>,
        val bestMovesUci: Map<Int, String>,
        val semanticByMoveIndex: Map<Int, MistakeAnalysis>,
        val firstMoveIsWhite: Boolean
    )

    // ==================== ДВУСТОРОННИЙ АНАЛИЗ ВСЕХ ХОДОВ ====================

    private suspend fun analyzeAllMoves(
        gameId: Long,
        pgn: String,
        analysisDepth: Int,
        onProgress: (current: Int, total: Int, moveNotation: String) -> Unit
    ): AnalysisData {
        val analyzedMoves = mutableListOf<AnalyzedMove>()
        val evaluations = mutableListOf<Int>()
        val fenPositions = mutableMapOf<Int, String>()
        val bestMovesUci = mutableMapOf<Int, String>()
        val semanticByMoveIndex = mutableMapOf<Int, MistakeAnalysis>()

        val normalized = ChessMoveUtils.normalizePgn(pgn)
        val halfMoves = parseMoves(normalized)

        if (halfMoves.isEmpty()) {
            throw IllegalArgumentException("В партии нет ходов")
        }

        val board = Board()
        val isCustomStartFen = normalized.startFen != null
        if (normalized.startFen != null) {
            board.loadFromFen(normalized.startFen)
        }

        val firstMoveIsWhite = board.sideToMove == Side.WHITE
        val startFullMove = board.moveCounter.coerceAtLeast(1)
        val totalMoves = halfMoves.size

        // Один поиск Stockfish на каждую позицию даёт и оценку, и лучшую линию для следующего полухода
        var pendingLine = chessEngine.getBestMoveWithLine(board.fen, depthLimit = analysisDepth)
            ?: throw EngineAnalysisException(
                "Движок не вернул оценку стартовой позиции. Проверьте движок и повторите разбор."
            )
        val initialEvalWhite = toWhitePerspective(pendingLine.score, board.sideToMove)
        evaluations.add(initialEvalWhite)

        var inBookSequence = !isCustomStartFen
        var lastAnnouncedOpeningName: String? = null
        var prevMove: Move? = null
        var prevCapturedValue = 0
        var prevPlyWinLoss = 0.0


        halfMoves.forEachIndexed { index, move ->
            coroutineContext.ensureActive()

            val moverSide = board.sideToMove
            val movingColor = if (moverSide == Side.WHITE) ChessColor.WHITE else ChessColor.BLACK
            val moveNumber = startFullMove + ((index + if (firstMoveIsWhite) 0 else 1) / 2)

            val fenBefore = board.fen
            fenPositions[index] = fenBefore

            val san = resolveMoveSan(move, fenBefore)
            onProgress(index + 1, totalMoves, san)

            // Снимаем тактический контекст ДО применения хода на доске
            val beforeLine = pendingLine
            val bestMoveUci = beforeLine?.bestMove.orEmpty()
            val playedMoveUci = ChessMoveUtils.moveToUci(move)
            val capturedValueNow = capturedMaterialValue(board, move)
            val isAutoRecapture = GameMoveClassifier.isAutomaticRecapture(
                boardBefore = board,
                move = move,
                prevMove = prevMove,
                prevCapturedValue = prevCapturedValue
            )
            val wasInCheckWithChoices = board.isKingAttacked &&
                runCatching { board.legalMoves().size >= 3 }.getOrDefault(false)
            val sacrifice = GameMoveClassifier.detectMaterialSacrifice(board, move)

            // Копия доски до хода для семантического разбора ошибок без повторного разбора PGN
            val boardSnapshotBefore = Board().apply { loadFromFen(fenBefore) }

            if (!board.doMove(move)) {
                throw IllegalArgumentException("Нелегальный ход в PGN на ходу $moveNumber: $san")
            }

            val fenAfter = board.fen

            try {
                val causedStalemate = !board.isMated && board.isStaleMate

                // Точная обработка терминальных позиций (мат и пат/ничья) без лишнего вызова движка
                val afterLine: AnalysisLine? = when {
                    board.isMated -> AnalysisLine(
                        bestMove = "",
                        score = -ChessEngine.MATE_VALUE,
                        mateIn = 0,
                        principalVariation = emptyList()
                    )
                    causedStalemate || board.isDraw -> AnalysisLine(
                        bestMove = "",
                        score = 0,
                        mateIn = null,
                        principalVariation = emptyList()
                    )
                    else -> chessEngine.getBestMoveWithLine(fenAfter, depthLimit = analysisDepth)
                }

                // Терминальные позиции обработаны выше, поэтому здесь отсутствие линии
                // означает сбой движка, а не «нулевую» оценку. Молчаливая подстановка 0
                // искажала бы точность и число ошибок.
                val evalAfterRaw = afterLine?.score
                    ?: throw EngineAnalysisException(
                        "Движок не вернул оценку позиции после хода $moveNumber ($san)"
                    )
                pendingLine = afterLine

                val evalBefore = evaluations.last()
                val evalAfter = toWhitePerspective(evalAfterRaw, board.sideToMove)
                evaluations.add(evalAfter)

                val playedEngineBest = bestMoveUci.isNotEmpty() &&
                    playedMoveUci.equals(bestMoveUci, ignoreCase = true)

                val bookMatch = if (
                    !isCustomStartFen &&
                    moveNumber <= GameMoveClassifier.BOOK_MAX_FULL_MOVE
                ) {
                    OpeningDatabase.matchBookMove(fenAfter)
                } else {
                    null
                }

                val classification = GameMoveClassifier.classifyMove(
                    evalBefore = evalBefore,
                    evalAfter = evalAfter,
                    movingColor = movingColor,
                    moveNumber = moveNumber,
                    playedEngineBest = playedEngineBest,
                    inBookSequence = inBookSequence,
                    bookMatch = bookMatch,
                    sacrifice = sacrifice,
                    prevOpponentWinLoss = prevPlyWinLoss,
                    isAutomaticRecapture = isAutoRecapture,
                    wasInCheckWithChoices = wasInCheckWithChoices
                )

                val quality = classification.quality
                inBookSequence = quality == MoveQuality.BOOK

                val bestMoveSan = if (quality.isBad() && bestMoveUci.isNotEmpty() && !playedEngineBest) {
                    ChessMoveUtils.uciToSan(bestMoveUci, fenBefore).takeIf { it.isNotBlank() && it != san }
                } else {
                    null
                }

                if (bestMoveSan != null) {
                    bestMovesUci[index] = bestMoveUci
                }

                val semanticAnalysis = if (quality.isBad() && beforeLine != null && afterLine != null) {
                    runCatching {
                        semanticAnalyzer.analyzeComprehensive(
                            boardBefore = boardSnapshotBefore,
                            playerMove = move,
                            bestMoveLine = beforeLine,
                            punishmentLine = afterLine
                        )
                    }.getOrNull()?.also { semanticByMoveIndex[index] = it }
                } else {
                    null
                }

                val newlyEnteredOpening = quality == MoveQuality.BOOK &&
                    bookMatch?.openingName != null &&
                    bookMatch.openingName != lastAnnouncedOpeningName
                if (quality == MoveQuality.BOOK && bookMatch?.openingName != null) {
                    lastAnnouncedOpeningName = bookMatch.openingName
                }

                val comment = buildMoveComment(
                    classification = classification,
                    movingColor = movingColor,
                    moveNumber = moveNumber,
                    fenBefore = fenBefore,
                    fenAfter = fenAfter,
                    evalAfterWhite = evalAfter,
                    bestMoveSan = bestMoveSan,
                    beforeLine = beforeLine,
                    afterLine = afterLine,
                    semanticAnalysis = semanticAnalysis,
                    bookMatch = bookMatch,
                    isNewOpeningTabiya = newlyEnteredOpening,
                    causedStalemate = causedStalemate
                )

                analyzedMoves.add(
                    AnalyzedMove(
                        gameId = gameId,
                        moveIndex = index,
                        moveNumber = moveNumber,
                        color = movingColor,
                        quality = quality,
                        san = san,
                        bestMove = bestMoveSan,
                        evalBefore = evalBefore,
                        evalAfter = evalAfter,
                        evalLoss = classification.rawCpLoss,
                        comment = comment
                    )
                )

                prevMove = move
                prevCapturedValue = capturedValueNow
                prevPlyWinLoss = if (quality == MoveQuality.BOOK) 0.0 else classification.winPercentLoss
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Сбой одного поиска делает агрегаты по всей партии недостоверными,
                // поэтому останавливаемся сразу: остальные ходы не достоверны и не считаются.
                Log.e(TAG, "Error analyzing move $moveNumber ($san): ${e.message}", e)
                throw EngineAnalysisException(
                    "Не удалось оценить позицию на ходу $moveNumber ($san): " +
                        (e.message ?: "неизвестная ошибка"),
                    e
                )
            }
        }

        return AnalysisData(
            analyzedMoves = analyzedMoves,
            evaluations = evaluations,
            fenPositions = fenPositions,
            bestMovesUci = bestMovesUci,
            semanticByMoveIndex = semanticByMoveIndex,
            firstMoveIsWhite = firstMoveIsWhite
        )
    }

    // ==================== РАЗБОР PGN И ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ====================

    private fun parseMoves(normalized: ChessMoveUtils.NormalizedPgn): List<Move> {
        val tempFile = File.createTempFile("analyze", ".pgn")
        return try {
            tempFile.writeText(normalized.document)
            val pgnHolder = PgnHolder(tempFile.absolutePath)
            pgnHolder.loadPgn()
            val pgnMoves = pgnHolder.games.firstOrNull()?.halfMoves?.toList().orEmpty()
            if (pgnMoves.isNotEmpty()) {
                pgnMoves
            } else {
                parseMovesFallback(normalized)
            }
        } catch (e: Exception) {
            Log.w(TAG, "PgnHolder failed, attempting MoveList SAN fallback", e)
            parseMovesFallback(normalized)
        } finally {
            tempFile.delete()
        }
    }

    private fun parseMovesFallback(normalized: ChessMoveUtils.NormalizedPgn): List<Move> {
        val moveList = if (normalized.startFen != null) {
            MoveList(normalized.startFen)
        } else {
            MoveList()
        }
        moveList.loadFromSan(normalized.movesText)
        return moveList.toList()
    }

    private fun resolveMoveSan(move: Move, fenBefore: String): String {
        val existing = move.san?.takeIf { it.isNotBlank() }
        if (existing != null) return existing
        return ChessMoveUtils.uciToSan(ChessMoveUtils.moveToUci(move), fenBefore)
    }

    private fun toWhitePerspective(scoreForSideToMove: Int, sideToMove: Side): Int {
        return if (sideToMove == Side.BLACK) -scoreForSideToMove else scoreForSideToMove
    }

    private fun capturedMaterialValue(boardBefore: Board, move: Move): Int {
        val moving = boardBefore.getPiece(move.from)
        val target = boardBefore.getPiece(move.to)
        if (moving.pieceType == PieceType.PAWN && move.from.file != move.to.file && target == Piece.NONE) {
            return ChessMoveUtils.pieceValue(PieceType.PAWN)
        }
        if (target != Piece.NONE && target.pieceSide != boardBefore.sideToMove) {
            return ChessMoveUtils.pieceValue(target.pieceType)
        }
        return 0
    }

    // ==================== ТЕМАТИЧЕСКАЯ КЛАССИФИКАЦИЯ И ТРЕНЕРСКИЕ КОММЕНТАРИИ ====================

    private fun detectThemeId(
        move: AnalyzedMove,
        fenBefore: String,
        bestMoveUci: String,
        semantic: MistakeAnalysis?
    ): Long {
        // 1. Точный тактический мотив из комплексного разбора (учитывает и лучшую линию, и линию наказания)
        val motif = semantic?.tacticalMotif ?: run {
            if (fenBefore.isNotBlank() && bestMoveUci.isNotBlank()) {
                runCatching {
                    val b = Board().apply { loadFromFen(fenBefore) }
                    semanticAnalyzer.detectMotif(b, bestMoveUci)
                }.getOrNull()
            } else {
                null
            }
        }

        when (motif) {
            TacticalMotif.FORK -> return PredefinedThemes.FORK.id!!
            TacticalMotif.PIN -> return PredefinedThemes.PIN.id!!
            TacticalMotif.SKEWER -> return PredefinedThemes.SKEWER.id!!
            TacticalMotif.DISCOVERED_ATTACK,
            TacticalMotif.DISCOVERED_CHECK -> return PredefinedThemes.DISCOVERED_ATTACK.id!!
            TacticalMotif.BACK_RANK,
            TacticalMotif.SMOTHERED_MATE -> return PredefinedThemes.BACK_RANK.id!!
            TacticalMotif.REMOVING_DEFENDER,
            TacticalMotif.DEFLECTION,
            TacticalMotif.OVERLOADING -> return PredefinedThemes.SACRIFICE.id!!
            else -> {}
        }

        // 2. Категория угрозы мата или раскрытия короля
        if (semantic?.category == MistakeCategory.MATE_THREAT ||
            semantic?.category == MistakeCategory.KING_SAFETY ||
            ChessEngine.isMateScore(move.evalAfter)
        ) {
            return PredefinedThemes.KING_SAFETY.id!!
        }

        // 3. Фаза партии с учётом реального материала на доске (а не жёсткого номера хода)
        if (fenBefore.isNotBlank()) {
            if (GameMoveClassifier.isPurePawnEndgame(fenBefore)) {
                return PredefinedThemes.PAWN_ENDGAME.id!!
            }
            return when (GameMoveClassifier.detectSinglePositionPhase(fenBefore, move.moveNumber)) {
                GameMoveClassifier.GamePhase.OPENING -> PredefinedThemes.OPENING_PRINCIPLES.id!!
                GameMoveClassifier.GamePhase.ENDGAME -> PredefinedThemes.KING_ACTIVITY.id!!
                GameMoveClassifier.GamePhase.MIDDLEGAME -> PredefinedThemes.PIECE_ACTIVITY.id!!
            }
        }

        return PredefinedThemes.PIECE_ACTIVITY.id!!
    }

    private fun buildMoveComment(
        classification: GameMoveClassifier.MoveClassification,
        movingColor: ChessColor,
        moveNumber: Int,
        fenBefore: String,
        fenAfter: String,
        evalAfterWhite: Int,
        bestMoveSan: String?,
        beforeLine: AnalysisLine?,
        afterLine: AnalysisLine?,
        semanticAnalysis: MistakeAnalysis?,
        bookMatch: BookMoveMatch?,
        isNewOpeningTabiya: Boolean,
        causedStalemate: Boolean = false
    ): String {
        val quality = classification.quality
        val moverAfter = if (movingColor == ChessColor.WHITE) evalAfterWhite else -evalAfterWhite

        return when (quality) {
            MoveQuality.BOOK -> {
                val openingName = classification.bookOpeningName
                val eco = classification.bookEco
                if (openingName != null) {
                    val header = if (!eco.isNullOrBlank()) {
                        "Теория: $openingName ($eco)."
                    } else {
                        "Теория: $openingName."
                    }
                    val idea = if (isNewOpeningTabiya) {
                        bookMatch?.opening?.keyIdeas?.firstOrNull()
                    } else {
                        null
                    }
                    if (!idea.isNullOrBlank()) "$header Идея: ${idea.replaceFirstChar { it.lowercase() }}." else header
                } else {
                    "Теоретический дебютный ход."
                }
            }

            MoveQuality.BRILLIANT -> {
                val sac = classification.sacrifice
                val base = if (sac != null) {
                    when (sac.kind) {
                        GameMoveClassifier.SacrificeKind.DIRECT_PIECE ->
                            "Блестящая жертва ${sac.pieceNameGenitive()}! Сильнейшее продолжение."
                        GameMoveClassifier.SacrificeKind.INDIRECT_HANGING ->
                            "Блестящий ход с тактической жертвой ${sac.pieceNameGenitive()}!"
                    }
                } else {
                    "Блестящая тактическая находка!"
                }
                val mateSuffix = if (ChessEngine.isMateScore(moverAfter) && moverAfter > 0) {
                    val m = abs(ChessEngine.getMateInMoves(moverAfter))
                    if (m == 0) " Мат на доске!" else " Ведёт к мату в $m ${pluralMoves(m)}!"
                } else {
                    ""
                }
                base + mateSuffix
            }

            MoveQuality.GREAT_MOVE -> {
                when (classification.greatReason) {
                    GameMoveClassifier.GreatMoveReason.PUNISHED_OPPONENT_MISTAKE ->
                        "Отличный ход! Точно наказывает ошибку соперника."
                    GameMoveClassifier.GreatMoveReason.FOUND_FORCED_MATE -> {
                        val m = abs(ChessEngine.getMateInMoves(moverAfter))
                        if (m > 0) {
                            "Отличный ход! Находит форсированный мат в $m ${pluralMoves(m)}."
                        } else {
                            "Отличный ход с матовой атакой!"
                        }
                    }
                    GameMoveClassifier.GreatMoveReason.CRITICAL_DEFENSE ->
                        "Точная защита под шахом в сложной позиции!"
                    null -> "Отличный ход!"
                }
            }

            MoveQuality.BEST_MOVE -> {
                if (ChessEngine.isMateScore(moverAfter) && moverAfter > 0) {
                    val m = abs(ChessEngine.getMateInMoves(moverAfter))
                    if (m == 0) "Мат!" else "Точный ход к мату в $m ${pluralMoves(m)}."
                } else {
                    "Сильнейший ход по оценке движка."
                }
            }

            MoveQuality.EXCELLENT -> "Очень точный ход, сохраняющий качество позиции."

            MoveQuality.GOOD -> "Крепкий практичный ход."

            MoveQuality.INACCURACY,
            MoveQuality.MISTAKE,
            MoveQuality.MISSED_WIN,
            MoveQuality.BLUNDER -> buildBadMoveComment(
                classification = classification,
                movingColor = movingColor,
                moveNumber = moveNumber,
                fenBefore = fenBefore,
                fenAfter = fenAfter,
                bestMoveSan = bestMoveSan,
                beforeLine = beforeLine,
                afterLine = afterLine,
                semanticAnalysis = semanticAnalysis,
                causedStalemate = causedStalemate
            )
        }
    }

    private fun buildBadMoveComment(
        classification: GameMoveClassifier.MoveClassification,
        movingColor: ChessColor,
        moveNumber: Int,
        fenBefore: String,
        fenAfter: String,
        bestMoveSan: String?,
        beforeLine: AnalysisLine?,
        afterLine: AnalysisLine?,
        semanticAnalysis: MistakeAnalysis?,
        causedStalemate: Boolean = false
    ): String {
        val parts = mutableListOf<String>()

        if (causedStalemate && classification.winBefore >= 70.0) {
            parts.add("Допущен пат в выигранной позиции — победа упущена!")
        } else if (classification.unpunishedOpponentMistake) {
            parts.add("Упущен шанс наказать ошибку соперника!")
        }

        // Основное смысловое объяснение причины ошибки
        val semanticText = if (causedStalemate && classification.winBefore >= 70.0) {
            ""
        } else {
            semanticAnalysis?.explanation?.trim().orEmpty()
        }
        if (semanticText.isNotEmpty()) {
            // Если это ошибка или зевок, не называем её мягким словом «неточность»
            val adjusted = if (
                semanticAnalysis?.category == MistakeCategory.POSITIONAL &&
                (classification.quality == MoveQuality.MISTAKE || classification.quality == MoveQuality.BLUNDER)
            ) {
                if (classification.quality == MoveQuality.BLUNDER) {
                    "Серьёзная уступка позиции: оценка резко меняется в пользу соперника."
                } else {
                    "Позиционная ошибка: соперник получает устойчивый перевес."
                }
            } else if (
                classification.quality == MoveQuality.INACCURACY &&
                beforeLine?.mateIn != null &&
                beforeLine.mateIn > 0 &&
                classification.winAfter >= 84.0
            ) {
                val m = beforeLine.mateIn
                "Был форсированный мат в $m ${pluralMoves(m)}, но позиция остаётся легко выигранной."
            } else {
                semanticText
            }
            parts.add(adjusted)
        } else {
            val pawns = String.format(Locale.US, "%.1f", classification.cappedCpLoss / 100.0)
            parts.add(
                when (classification.quality) {
                    MoveQuality.INACCURACY -> "Неточность (−$pawns)."
                    MoveQuality.MISTAKE -> "Ошибка (−$pawns)."
                    MoveQuality.MISSED_WIN -> "Упущен решающий перевес (−$pawns)."
                    else -> "Грубый зевок! (−$pawns)"
                }
            )
        }

        // Краткий вариант сильнейшего продолжения в SAN
        val bestPvText = formatPvSan(
            startFen = fenBefore,
            pvUci = beforeLine?.principalVariation.orEmpty(),
            startMoveNumber = moveNumber,
            startColor = movingColor,
            maxPlies = PV_PREVIEW_PLIES
        ).ifBlank { bestMoveSan.orEmpty() }

        if (bestPvText.isNotBlank()) {
            parts.add("Сильнее: $bestPvText.")
        }

        // Для ошибок и зевков показываем короткое опровержение соперника
        if (classification.quality == MoveQuality.BLUNDER || classification.quality == MoveQuality.MISTAKE) {
            val nextMoveNumber = if (movingColor == ChessColor.BLACK) moveNumber + 1 else moveNumber
            val refutationText = formatPvSan(
                startFen = fenAfter,
                pvUci = afterLine?.principalVariation.orEmpty(),
                startMoveNumber = nextMoveNumber,
                startColor = movingColor.opposite(),
                maxPlies = REFUTATION_PREVIEW_PLIES
            )
            if (refutationText.isNotBlank()) {
                parts.add("Опровержение: $refutationText.")
            }
        }

        return parts.joinToString(" ")
    }

    /**
     * Форматирует первые [maxPlies] полуходов UCI-варианта в читаемую шахматную нотацию SAN
     * с номерами ходов (например: "14. Nxd5 exd5 15. Bb5+").
     */
    private fun formatPvSan(
        startFen: String,
        pvUci: List<String>,
        startMoveNumber: Int,
        startColor: ChessColor,
        maxPlies: Int
    ): String {
        if (startFen.isBlank() || pvUci.isEmpty() || maxPlies <= 0) return ""
        return runCatching {
            val sim = Board().apply { loadFromFen(startFen) }
            val tokens = mutableListOf<String>()
            var moveNum = startMoveNumber
            var isWhite = startColor == ChessColor.WHITE

            for ((idx, uci) in pvUci.take(maxPlies).withIndex()) {
                val parsed = ChessMoveUtils.parseUciMove(sim, uci) ?: break
                val fenNow = sim.fen
                val san = ChessMoveUtils.uciToSan(uci, fenNow).takeIf { it.isNotBlank() } ?: break
                if (!sim.doMove(parsed)) break

                val token = when {
                    isWhite -> "$moveNum. $san"
                    idx == 0 -> "$moveNum... $san"
                    else -> san
                }
                tokens.add(token)

                if (!isWhite) moveNum++
                isWhite = !isWhite
            }
            tokens.joinToString(" ")
        }.getOrDefault("")
    }

    private fun pluralMoves(n: Int): String {
        val absN = abs(n) % 100
        val last = absN % 10
        return when {
            absN in 11..19 -> "ходов"
            last == 1 -> "ход"
            last in 2..4 -> "хода"
            else -> "ходов"
        }
    }
}
