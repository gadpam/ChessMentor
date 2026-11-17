// domain/usecase/AnalyzeGameUseCase.kt
package com.example.chessmentor.domain.usecase

import android.util.Log
import com.example.chessmentor.data.engine.ChessEngine
import com.example.chessmentor.domain.analysis.SemanticMistakeAnalyzer
import com.example.chessmentor.domain.analysis.TacticalMotif
import com.example.chessmentor.domain.entity.*
import com.example.chessmentor.domain.opening.OpeningDatabase
import com.example.chessmentor.domain.opening.OpeningMatch
import com.example.chessmentor.domain.repository.AnalyzedMoveRepository
import com.example.chessmentor.domain.repository.EngineSettingsRepository
import com.example.chessmentor.domain.repository.GameRepository
import com.example.chessmentor.domain.repository.MistakeRepository
import com.example.chessmentor.domain.repository.UserRepository
import com.example.chessmentor.domain.util.ChessMoveUtils
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.move.Move
import com.github.bhlangonijr.chesslib.move.MoveList
import com.github.bhlangonijr.chesslib.pgn.PgnHolder
import java.io.File
import java.util.Collections
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.exp
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

        // ========== ПОРОГИ ОШИБОК ==========
        private const val BLUNDER_THRESHOLD = 300    // 3+ пешки
        private const val MISTAKE_THRESHOLD = 100    // 1-3 пешки
        private const val INACCURACY_THRESHOLD = 30  // 0.3-1 пешки

        // ========== ПОРОГИ ХОРОШИХ ХОДОВ ==========
        private const val BEST_MOVE_MAX_LOSS = 5     // Потеря до 5cp
        private const val EXCELLENT_MAX_LOSS = 15    // Потеря до 15cp
        private const val GOOD_MAX_LOSS = 29         // Потеря до 29cp

        // ========== ПОЗИЦИОННЫЕ ПОРОГИ ==========
        private const val WINNING_POSITION = 300
        private const val EQUAL_POSITION_MAX = 80
        private const val BOOK_MOVES_LIMIT = 10
        private const val MAX_ACPL_MOVE_LOSS = 1000
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
     * Основной метод анализа с прогрессом
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

            withContext(Dispatchers.IO) {
                chessEngine.setOption("Threads", engineSettings.threads.toString())
                chessEngine.setOption("Hash", engineSettings.hashSizeMb.toString())
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
                            playerColor = game.playerColor,
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
            analyzedMoveRepository.deleteByGameId(gameId)
            mistakeRepository.deleteByGameId(gameId)

            val savedMoves = analyzedMoveRepository.saveAll(data.analyzedMoves)

            val mistakes = savedMoves
                .filter { it.isMistake() }
                .mapNotNull { move ->
                    val fenBefore = data.fenPositions[move.moveIndex].orEmpty()
                    val bestMoveUci = data.bestMovesUci[move.moveIndex].orEmpty()
                    move.toMistake(
                        themeId = detectThemeId(move, fenBefore, bestMoveUci),
                        fenBefore = fenBefore
                    )
                }

            val savedMistakes = mistakeRepository.saveAll(mistakes)

            val blunders = savedMoves.count { it.quality == MoveQuality.BLUNDER }
            val mistakesCount = savedMoves.count {
                it.quality == MoveQuality.MISTAKE || it.quality == MoveQuality.MISSED_WIN
            }
            val inaccuracies = savedMoves.count { it.quality == MoveQuality.INACCURACY }

            val accuracy = calculateCapsAccuracy(data.evaluations, game.playerColor, data.firstMoveIsWhite)
            val avgLoss = calculateAverageLoss(data.evaluations, game.playerColor, data.firstMoveIsWhite)

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
                "Final: accuracy=${String.format(Locale.US, "%.1f", accuracy)}%, " +
                    "blunders=$blunders, mistakes=$mistakesCount, inaccuracies=$inaccuracies"
            )

            emit(
                AnalysisProgress.Completed(
                    Result.Success(finalGame, savedMoves, savedMistakes, data.evaluations)
                )
            )
        } catch (e: CancellationException) {
            // При отмене возвращаем партию в PENDING, чтобы её можно было запустить снова
            gameInProgress?.let { inProgress ->
                withContext(NonCancellable) {
                    runCatching {
                        gameRepository.update(inProgress.copy(analysisStatus = AnalysisStatus.PENDING))
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

    private data class AnalysisData(
        val analyzedMoves: List<AnalyzedMove>,
        val evaluations: List<Int>,
        val fenPositions: Map<Int, String>,
        val bestMovesUci: Map<Int, String>,
        val firstMoveIsWhite: Boolean
    )

    // ==================== АНАЛИЗ ВСЕХ ХОДОВ ====================

    private suspend fun analyzeAllMoves(
        gameId: Long,
        pgn: String,
        playerColor: ChessColor,
        analysisDepth: Int,
        onProgress: (current: Int, total: Int, moveNotation: String) -> Unit
    ): AnalysisData {
        val analyzedMoves = mutableListOf<AnalyzedMove>()
        val evaluations = mutableListOf<Int>()
        val fenPositions = mutableMapOf<Int, String>()
        val bestMovesUci = mutableMapOf<Int, String>()

        val normalized = ChessMoveUtils.normalizePgn(pgn)
        val halfMoves = parseMoves(normalized)

        if (halfMoves.isEmpty()) {
            throw IllegalArgumentException("В партии нет ходов")
        }

        val board = Board()
        if (normalized.startFen != null) {
            board.loadFromFen(normalized.startFen)
        }

        val firstMoveIsWhite = board.sideToMove == Side.WHITE
        val startFullMove = board.moveCounter.coerceAtLeast(1)
        val totalMoves = halfMoves.size

        // Один поиск на каждую позицию даёт и оценку, и лучший ход для следующего полухода
        val initialLine = chessEngine.getBestMoveWithLine(board.fen, depthLimit = analysisDepth)
        val initialEvalWhite = toWhitePerspective(initialLine?.score ?: 0, board.sideToMove)
        var pendingBestMoveUci = initialLine?.bestMove.orEmpty()
        evaluations.add(initialEvalWhite)

        halfMoves.forEachIndexed { index, move ->
            coroutineContext.ensureActive()

            val isWhiteMove = board.sideToMove == Side.WHITE
            val movingColor = if (isWhiteMove) ChessColor.WHITE else ChessColor.BLACK
            val isPlayerMove = playerColor == movingColor
            val moveNumber = startFullMove + ((index + if (firstMoveIsWhite) 0 else 1) / 2)

            val fenBefore = board.fen
            fenPositions[index] = fenBefore

            val san = resolveMoveSan(move, fenBefore)
            onProgress(index + 1, totalMoves, san)

            val bestMoveUci = pendingBestMoveUci
            val userMoveUci = ChessMoveUtils.moveToUci(move)
            val isSacrifice = isMaterialSacrifice(board, move)

            if (!board.doMove(move)) {
                throw IllegalArgumentException("Нелегальный ход в PGN на ходу $moveNumber: $san")
            }

            val fenAfter = board.fen

            try {
                val afterLine = chessEngine.getBestMoveWithLine(fenAfter, depthLimit = analysisDepth)
                val evalAfterRaw = afterLine?.score
                    ?: chessEngine.evaluate(fenAfter, depthLimit = analysisDepth)
                pendingBestMoveUci = afterLine?.bestMove.orEmpty()

                val evalAfter = toWhitePerspective(evalAfterRaw, board.sideToMove)
                evaluations.add(evalAfter)

                if (isPlayerMove) {
                    val evalBefore = evaluations[evaluations.size - 2]
                    val playedEngineBest = bestMoveUci.isNotEmpty() &&
                        userMoveUci.equals(bestMoveUci, ignoreCase = true)
                    val isKnownBook = moveNumber <= BOOK_MOVES_LIMIT &&
                        OpeningDatabase.findOpening(fenAfter) is OpeningMatch.Found

                    val quality = classifyMove(
                        evalBefore = evalBefore,
                        evalAfter = evalAfter,
                        playerColor = playerColor,
                        moveNumber = moveNumber,
                        playedEngineBest = playedEngineBest,
                        isSacrifice = isSacrifice,
                        isKnownBook = isKnownBook
                    )

                    if (quality != null) {
                        val cpLoss = calculateRawLoss(evalBefore, evalAfter, playerColor)
                        val bestMoveSan = if (quality.isBad() && bestMoveUci.isNotEmpty() && !playedEngineBest) {
                            ChessMoveUtils.uciToSan(bestMoveUci, fenBefore).takeIf { it != san }
                        } else {
                            null
                        }

                        if (bestMoveSan != null) {
                            bestMovesUci[index] = bestMoveUci
                        }

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
                                evalLoss = cpLoss,
                                comment = generateComment(quality, cpLoss, evalBefore, evalAfter, playerColor)
                            )
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error analyzing move $moveNumber: ${e.message}", e)
                pendingBestMoveUci = ""
                evaluations.add(evaluations.lastOrNull() ?: 0)
            }
        }

        return AnalysisData(
            analyzedMoves = analyzedMoves,
            evaluations = evaluations,
            fenPositions = fenPositions,
            bestMovesUci = bestMovesUci,
            firstMoveIsWhite = firstMoveIsWhite
        )
    }

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

    private fun isMaterialSacrifice(board: Board, move: Move): Boolean {
        val piece = board.getPiece(move.from)
        if (piece == Piece.NONE) return false
        val pieceVal = ChessMoveUtils.pieceValue(piece.pieceType)
        if (pieceVal < 300) return false

        val captured = board.getPiece(move.to)
        val capturedVal = if (captured != Piece.NONE) ChessMoveUtils.pieceValue(captured.pieceType) else 0
        if (capturedVal >= pieceVal) return false

        val sim = Board().apply {
            loadFromFen(board.fen)
            if (!doMove(move)) return false
        }
        val opponentAttacked = sim.squareAttackedBy(move.to, piece.pieceSide.flip()) != 0L
        return opponentAttacked && (pieceVal - capturedVal) >= 200
    }

    // ==================== КЛАССИФИКАЦИЯ ХОДА ====================

    private fun classifyMove(
        evalBefore: Int,
        evalAfter: Int,
        playerColor: ChessColor,
        moveNumber: Int,
        playedEngineBest: Boolean,
        isSacrifice: Boolean,
        isKnownBook: Boolean
    ): MoveQuality? {
        val playerBefore = if (playerColor == ChessColor.WHITE) evalBefore else -evalBefore
        val playerAfter = if (playerColor == ChessColor.WHITE) evalAfter else -evalAfter

        // Если игрок сделал лучший ход движка, он не может быть ошибкой (защита от эффекта горизонта)
        if (playedEngineBest) {
            return when {
                isKnownBook -> MoveQuality.BOOK
                isSacrifice && playerAfter >= 100 -> MoveQuality.BRILLIANT
                moveNumber > BOOK_MOVES_LIMIT && playerBefore in -120..250 && playerAfter >= 150 && isSacrifice ->
                    MoveQuality.BRILLIANT
                moveNumber <= BOOK_MOVES_LIMIT -> MoveQuality.BOOK
                else -> MoveQuality.BEST_MOVE
            }
        }

        if (isKnownBook && (playerBefore - playerAfter) < INACCURACY_THRESHOLD) {
            return MoveQuality.BOOK
        }

        // Допущен мат соперника, которого раньше не было
        val allowedNewMate = ChessEngine.isMateScore(playerAfter) && playerAfter < 0 &&
            !(ChessEngine.isMateScore(playerBefore) && playerBefore < 0)
        if (allowedNewMate) {
            return MoveQuality.BLUNDER
        }

        // Упущен форсированный мат или большой перевес сведён к равенству
        val missedForcedMate = ChessEngine.isMateScore(playerBefore) && playerBefore > 0 &&
            !(ChessEngine.isMateScore(playerAfter) && playerAfter > 0)
        if (missedForcedMate) {
            return if (playerAfter >= 150) MoveQuality.MISSED_WIN else MoveQuality.BLUNDER
        }

        val cpLoss = (cappedCp(playerBefore) - cappedCp(playerAfter)).coerceAtLeast(0)
        if (playerBefore >= WINNING_POSITION && playerAfter in -50..EQUAL_POSITION_MAX && cpLoss >= MISTAKE_THRESHOLD) {
            return MoveQuality.MISSED_WIN
        }

        return when {
            cpLoss >= BLUNDER_THRESHOLD -> MoveQuality.BLUNDER
            cpLoss >= MISTAKE_THRESHOLD -> MoveQuality.MISTAKE
            cpLoss >= INACCURACY_THRESHOLD -> MoveQuality.INACCURACY
            moveNumber <= BOOK_MOVES_LIMIT && cpLoss <= EXCELLENT_MAX_LOSS -> MoveQuality.BOOK
            isSacrifice && cpLoss <= EXCELLENT_MAX_LOSS && playerAfter >= 150 -> MoveQuality.GREAT_MOVE
            cpLoss <= BEST_MOVE_MAX_LOSS -> MoveQuality.BEST_MOVE
            cpLoss <= EXCELLENT_MAX_LOSS -> MoveQuality.EXCELLENT
            cpLoss <= GOOD_MAX_LOSS -> MoveQuality.GOOD
            else -> null
        }
    }

    private fun cappedCp(score: Int): Int {
        if (ChessEngine.isMateScore(score)) {
            return if (score > 0) MAX_ACPL_MOVE_LOSS else -MAX_ACPL_MOVE_LOSS
        }
        return score.coerceIn(-MAX_ACPL_MOVE_LOSS, MAX_ACPL_MOVE_LOSS)
    }

    private fun calculateRawLoss(evalBefore: Int, evalAfter: Int, playerColor: ChessColor): Int {
        val playerBefore = if (playerColor == ChessColor.WHITE) evalBefore else -evalBefore
        val playerAfter = if (playerColor == ChessColor.WHITE) evalAfter else -evalAfter
        if ((ChessEngine.isMateScore(playerAfter) && playerAfter < 0) ||
            (ChessEngine.isMateScore(playerBefore) && playerBefore > 0 && !ChessEngine.isMateScore(playerAfter))
        ) {
            return ChessEngine.MATE_VALUE
        }
        return (cappedCp(playerBefore) - cappedCp(playerAfter)).coerceAtLeast(0)
    }

    private fun calculateCappedLoss(evalBefore: Int, evalAfter: Int, playerColor: ChessColor): Int {
        val playerBefore = cappedCp(if (playerColor == ChessColor.WHITE) evalBefore else -evalBefore)
        val playerAfter = cappedCp(if (playerColor == ChessColor.WHITE) evalAfter else -evalAfter)
        return (playerBefore - playerAfter).coerceIn(0, MAX_ACPL_MOVE_LOSS)
    }

    // ==================== CAPS ACCURACY ====================

    private fun centipawnsToWinProbability(cp: Int): Double {
        val capped = cappedCp(cp)
        return 50.0 + 50.0 * (2.0 / (1.0 + exp(-0.00368208 * capped)) - 1.0)
    }

    private fun calculateMoveAccuracy(cpBefore: Int, cpAfter: Int, playerColor: ChessColor): Double {
        val winBefore = if (playerColor == ChessColor.WHITE) {
            centipawnsToWinProbability(cpBefore)
        } else {
            centipawnsToWinProbability(-cpBefore)
        }

        val winAfter = if (playerColor == ChessColor.WHITE) {
            centipawnsToWinProbability(cpAfter)
        } else {
            centipawnsToWinProbability(-cpAfter)
        }

        if (winAfter >= winBefore) return 100.0

        val winDrop = winBefore - winAfter
        val accuracy = 103.1668 * exp(-0.04354 * winDrop) - 3.1669

        return accuracy.coerceIn(0.0, 100.0)
    }

    private fun calculateCapsAccuracy(
        evaluations: List<Int>,
        playerColor: ChessColor,
        firstMoveIsWhite: Boolean
    ): Double {
        if (evaluations.size < 2) return 100.0

        var totalAccuracy = 0.0
        var playerMoveCount = 0

        for (i in 1 until evaluations.size) {
            val moveIndex = i - 1
            val isWhiteMove = if (firstMoveIsWhite) moveIndex % 2 == 0 else moveIndex % 2 != 0
            val isPlayerMove = (playerColor == ChessColor.WHITE && isWhiteMove) ||
                (playerColor == ChessColor.BLACK && !isWhiteMove)

            if (isPlayerMove) {
                totalAccuracy += calculateMoveAccuracy(evaluations[i - 1], evaluations[i], playerColor)
                playerMoveCount++
            }
        }

        return if (playerMoveCount > 0) totalAccuracy / playerMoveCount else 100.0
    }

    private fun calculateAverageLoss(
        evaluations: List<Int>,
        playerColor: ChessColor,
        firstMoveIsWhite: Boolean
    ): Int {
        if (evaluations.size < 2) return 0

        var totalLoss = 0
        var count = 0

        for (i in 1 until evaluations.size) {
            val moveIndex = i - 1
            val isWhiteMove = if (firstMoveIsWhite) moveIndex % 2 == 0 else moveIndex % 2 != 0
            val isPlayerMove = (playerColor == ChessColor.WHITE && isWhiteMove) ||
                (playerColor == ChessColor.BLACK && !isWhiteMove)

            if (isPlayerMove) {
                totalLoss += calculateCappedLoss(evaluations[i - 1], evaluations[i], playerColor)
                count++
            }
        }

        return if (count > 0) totalLoss / count else 0
    }

    // ==================== ТЕМЫ И КОММЕНТАРИИ ====================

    private fun detectThemeId(move: AnalyzedMove, fenBefore: String, bestMoveUci: String): Long {
        if (fenBefore.isNotBlank() && bestMoveUci.isNotBlank()) {
            runCatching {
                val board = Board().apply { loadFromFen(fenBefore) }
                when (semanticAnalyzer.detectMotif(board, bestMoveUci)) {
                    TacticalMotif.FORK -> return PredefinedThemes.FORK.id!!
                    TacticalMotif.PIN -> return PredefinedThemes.PIN.id!!
                    TacticalMotif.SKEWER -> return PredefinedThemes.SKEWER.id!!
                    TacticalMotif.DISCOVERED_ATTACK,
                    TacticalMotif.DISCOVERED_CHECK -> return PredefinedThemes.DISCOVERED_ATTACK.id!!
                    TacticalMotif.BACK_RANK,
                    TacticalMotif.SMOTHERED_MATE -> return PredefinedThemes.BACK_RANK.id!!
                    else -> {}
                }
                if (board.isKingAttacked) {
                    return PredefinedThemes.KING_SAFETY.id!!
                }
            }
        }

        return when {
            ChessEngine.isMateScore(move.evalAfter) -> PredefinedThemes.KING_SAFETY.id!!
            move.moveNumber <= 10 -> PredefinedThemes.OPENING_PRINCIPLES.id!!
            move.moveNumber >= 40 -> PredefinedThemes.KING_ACTIVITY.id!!
            else -> PredefinedThemes.PIECE_ACTIVITY.id!!
        }
    }

    private fun generateComment(
        quality: MoveQuality,
        cpLoss: Int,
        evalBefore: Int,
        evalAfter: Int,
        playerColor: ChessColor
    ): String {
        val playerBefore = if (playerColor == ChessColor.WHITE) evalBefore else -evalBefore
        val playerAfter = if (playerColor == ChessColor.WHITE) evalAfter else -evalAfter

        if (ChessEngine.isMateScore(playerAfter) && playerAfter < 0) {
            val moves = abs(ChessEngine.getMateInMoves(playerAfter))
            return if (moves > 0) "Допущен мат в $moves!" else "Допущен мат!"
        }
        if (ChessEngine.isMateScore(playerBefore) && playerBefore > 0 && !ChessEngine.isMateScore(playerAfter)) {
            return "Упущен форсированный мат!"
        }

        val pawns = String.format(Locale.US, "%.1f", abs(cpLoss) / 100.0)
        return when (quality) {
            MoveQuality.BRILLIANT -> "Блестящая жертва! Отличная находка."
            MoveQuality.GREAT_MOVE -> "Отличный ход!"
            MoveQuality.BEST_MOVE -> "Лучший ход."
            MoveQuality.EXCELLENT -> "Превосходно."
            MoveQuality.GOOD -> "Хороший ход."
            MoveQuality.BOOK -> "Теоретический ход."
            MoveQuality.INACCURACY -> "Неточность (−$pawns)."
            MoveQuality.MISTAKE -> "Ошибка (−$pawns)."
            MoveQuality.BLUNDER -> "Грубый зевок! (−$pawns)"
            MoveQuality.MISSED_WIN -> "Упущена победа! (−$pawns)"
        }
    }
}
