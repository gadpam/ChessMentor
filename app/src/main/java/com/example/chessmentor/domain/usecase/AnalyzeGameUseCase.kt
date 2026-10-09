// domain/usecase/AnalyzeGameUseCase.kt
package com.example.chessmentor.domain.usecase

import android.util.Log
import com.example.chessmentor.data.engine.ChessEngine
import com.example.chessmentor.domain.entity.*
import com.example.chessmentor.domain.repository.AnalyzedMoveRepository
import com.example.chessmentor.domain.repository.EngineSettingsRepository
import com.example.chessmentor.domain.repository.GameRepository
import com.example.chessmentor.domain.repository.MistakeRepository
import com.example.chessmentor.domain.repository.UserRepository
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.pgn.PgnHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import java.io.File
import kotlin.math.abs
import kotlin.math.exp

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

        // ========== ПОРОГИ ОШИБОК (Chess.com) ==========
        private const val BLUNDER_THRESHOLD = 300   // 3+ пешки
        private const val MISTAKE_THRESHOLD = 100   // 1-3 пешки
        private const val INACCURACY_THRESHOLD = 30 // 0.3-1 пешки

        // ========== ПОРОГИ ХОРОШИХ ХОДОВ ==========
        private const val BRILLIANT_THRESHOLD = 200    // Улучшение 2+ пешки в плохой позиции
        private const val GREAT_MOVE_THRESHOLD = 100   // Улучшение 1+ пешка
        private const val BEST_MOVE_MAX_LOSS = 5       // Потеря до 5cp
        private const val EXCELLENT_MAX_LOSS = 10      // Потеря до 10cp
        private const val GOOD_MAX_LOSS = 20           // Потеря до 20cp

        // ========== ПОЗИЦИОННЫЕ ПОРОГИ ==========
        private const val LOSING_POSITION = -150       // Проигранная позиция
        private const val BOOK_MOVES_LIMIT = 10        // Первые 10 ходов — дебют
    }

    data class Input(val gameId: Long)

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

        // ✅ НОВОЕ: Получаем настройки движка
        val engineSettings = engineSettingsRepository.getSettings()
        Log.i(TAG, "Engine settings: depth=${engineSettings.depth}, " +
                "threads=${engineSettings.threads}, hash=${engineSettings.hashSizeMb}MB")

        try {
            val game = gameRepository.findById(input.gameId)
            if (game == null) {
                emit(AnalysisProgress.Failed("Партия не найдена"))
                return@flow
            }

            if (game.analysisStatus == AnalysisStatus.COMPLETED) {
                val savedMoves = analyzedMoveRepository.findByGameId(game.id!!)
                val savedMistakes = mistakeRepository.findByGameId(game.id)
                val savedEvaluations = game.getEvaluations()

                Log.d(TAG, "Game already analyzed. Loaded ${savedMoves.size} moves, " +
                        "${savedMistakes.size} mistakes, ${savedEvaluations.size} evaluations")

                emit(AnalysisProgress.Completed(
                    Result.Success(game, savedMoves, savedMistakes, savedEvaluations)
                ))
                return@flow
            }

            if (game.analysisStatus == AnalysisStatus.IN_PROGRESS) {
                emit(AnalysisProgress.Failed("Партия уже анализируется"))
                return@flow
            }

            val user = userRepository.findById(game.userId)
            if (user == null) {
                emit(AnalysisProgress.Failed("Пользователь не найден"))
                return@flow
            }

            val gameInProgress = game.startAnalysis()
            gameRepository.update(gameInProgress)

            Log.d(TAG, "Initializing engine...")

            val engineReady = withContext(Dispatchers.IO) {
                chessEngine.init()
            }

            if (!engineReady) {
                val failedGame = gameInProgress.failAnalysis()
                gameRepository.update(failedGame)
                emit(AnalysisProgress.Failed("Не удалось запустить шахматный движок"))
                return@flow
            }

            // ✅ НОВОЕ: Применяем настройки движка
            withContext(Dispatchers.IO) {
                chessEngine.setOption("Threads", engineSettings.threads.toString())
                chessEngine.setOption("Hash", engineSettings.hashSizeMb.toString())
            }

            Log.i(TAG, "Engine initialized with settings, starting analysis...")

            val progressChannel = Channel<AnalysisProgress.InProgress>(Channel.BUFFERED)

            var analysisResult: AnalysisData? = null
            var analysisError: Exception? = null

            coroutineScope {
                val analysisJob = launch(Dispatchers.IO) {
                    try {
                        analysisResult = analyzeAllMoves(
                            gameId = game.id!!,
                            pgn = game.pgnData,
                            playerColor = game.playerColor,
                            analysisDepth = engineSettings.depth  // ✅ НОВОЕ: Передаём глубину
                        ) { current, total, moveNotation ->
                            progressChannel.trySend(
                                AnalysisProgress.InProgress(current, total, moveNotation)
                            )
                        }
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

            if (analysisError != null) {
                throw analysisError!!
            }

            val data = analysisResult ?: throw Exception("Анализ не вернул результат")

            Log.i(TAG, "Analysis complete: ${data.analyzedMoves.size} moves analyzed, " +
                    "${data.evaluations.size} evaluations")

            val savedMoves = analyzedMoveRepository.saveAll(data.analyzedMoves)

            val mistakes = data.analyzedMoves
                .filter { it.isMistake() }
                .mapNotNull { move ->
                    move.toMistake(
                        themeId = detectThemeId(move),
                        fenBefore = data.fenPositions.getOrElse(move.moveIndex) { "" }
                    )
                }

            val savedMistakes = mistakeRepository.saveAll(mistakes)

            val blunders = savedMoves.count { it.quality == MoveQuality.BLUNDER }
            val mistakesCount = savedMoves.count { it.quality == MoveQuality.MISTAKE }
            val inaccuracies = savedMoves.count { it.quality == MoveQuality.INACCURACY }

            val accuracy = calculateCapsAccuracy(data.evaluations, game.playerColor)
            val avgLoss = calculateAverageLoss(data.evaluations, game.playerColor)

            val analyzedGame = gameInProgress.completeAnalysis(
                accuracy = accuracy,
                avgLoss = avgLoss,
                blunders = blunders,
                mistakes = mistakesCount,
                inaccuracies = inaccuracies,
                evaluations = data.evaluations
            )

            val finalGame = gameRepository.update(analyzedGame)

            // Движок общий для приложения (AppContainer), поэтому не уничтожаем его здесь.

            Log.i(TAG, "Final: accuracy=${"%.1f".format(accuracy)}%, " +
                    "blunders=$blunders, mistakes=$mistakesCount, inaccuracies=$inaccuracies, " +
                    "brilliant=${savedMoves.count { it.quality == MoveQuality.BRILLIANT }}, " +
                    "great=${savedMoves.count { it.quality == MoveQuality.GREAT_MOVE }}, " +
                    "evaluations=${data.evaluations.size}")

            emit(AnalysisProgress.Completed(
                Result.Success(finalGame, savedMoves, savedMistakes, data.evaluations)
            ))

        } catch (e: Exception) {
            Log.e(TAG, "Analysis failed", e)

            emit(AnalysisProgress.Failed("Ошибка анализа: ${e.message}"))
        }
    }

    /**
     * Синхронный метод анализа (для обратной совместимости)
     */
    suspend fun execute(input: Input): Result = withContext(Dispatchers.Default) {
        var lastResult: Result = Result.Error("Анализ не завершён")

        executeWithProgress(input).collect { progress ->
            when (progress) {
                is AnalysisProgress.Completed -> lastResult = progress.result
                is AnalysisProgress.Failed -> lastResult = Result.Error(progress.error)
                else -> { }
            }
        }

        lastResult
    }

    // ==================== ВНУТРЕННИЕ КЛАССЫ ====================

    private data class AnalysisData(
        val analyzedMoves: List<AnalyzedMove>,
        val evaluations: List<Int>,
        val fenPositions: Map<Int, String>
    )

    // ==================== АНАЛИЗ ВСЕХ ХОДОВ ====================

    private suspend fun analyzeAllMoves(
        gameId: Long,
        pgn: String,
        playerColor: ChessColor,
        analysisDepth: Int,  // ✅ НОВОЕ: Параметр глубины
        onProgress: (current: Int, total: Int, moveNotation: String) -> Unit
    ): AnalysisData {
        val analyzedMoves = mutableListOf<AnalyzedMove>()
        val evaluations = mutableListOf<Int>()
        val fenPositions = mutableMapOf<Int, String>()

        try {
            val finalPgn = normalizePgn(pgn)

            val tempFile = File.createTempFile("analyze", ".pgn")
            tempFile.writeText(finalPgn)

            val pgnHolder = PgnHolder(tempFile.absolutePath)
            pgnHolder.loadPgn()
            tempFile.delete()

            if (pgnHolder.games.isEmpty()) {
                throw IllegalArgumentException("PGN не содержит партий")
            }

            val pgnGame = pgnHolder.games[0]
            val halfMoves = pgnGame.halfMoves

            if (halfMoves.isEmpty()) {
                throw IllegalArgumentException("В партии нет ходов")
            }

            Log.i(TAG, "Parsed ${halfMoves.size} half-moves, analyzing with depth=$analysisDepth")

            val board = Board()
            val totalMoves = halfMoves.size

            // ✅ ОБНОВЛЕНО: Используем analysisDepth
            val initialEval = chessEngine.evaluate(board.fen, depthLimit = analysisDepth)
            evaluations.add(initialEval)

            Log.i(TAG, "Initial evaluation: $initialEval cp")
            Log.i(TAG, "Analyzing $totalMoves half-moves...")

            halfMoves.forEachIndexed { index, move ->
                val moveNumber = (index / 2) + 1
                val isWhiteMove = index % 2 == 0
                val movingColor = if (isWhiteMove) ChessColor.WHITE else ChessColor.BLACK
                val isPlayerMove = (playerColor == movingColor)

                onProgress(index + 1, totalMoves, move.san)

                val fenBefore = board.fen
                fenPositions[index] = fenBefore

                var bestMoveUci = ""
                if (isPlayerMove) {
                    // ✅ ОБНОВЛЕНО: Используем analysisDepth
                    bestMoveUci = chessEngine.getBestMove(fenBefore, depthLimit = analysisDepth) ?: ""
                }

                board.doMove(move)
                val fenAfter = board.fen

                try {
                    // ✅ ОБНОВЛЕНО: Используем analysisDepth
                    val evalAfterRaw = chessEngine.evaluate(fenAfter, depthLimit = analysisDepth)

                    val sideToMoveAfter = if (fenAfter.contains(" w ")) "WHITE" else "BLACK"
                    val evalAfter = if (sideToMoveAfter == "BLACK") -evalAfterRaw else evalAfterRaw

                    evaluations.add(evalAfter)

                    if (isPlayerMove) {
                        val evalBefore = evaluations[evaluations.size - 2]

                        val quality = classifyMove(
                            evalBefore = evalBefore,
                            evalAfter = evalAfter,
                            playerColor = playerColor,
                            moveNumber = moveNumber
                        )

                        if (quality != null) {
                            val cpLoss = calculateLoss(evalBefore, evalAfter, playerColor)
                            val bestMoveSan = if (bestMoveUci.isNotEmpty()) {
                                convertUciToSan(bestMoveUci, fenBefore)
                            } else null

                            analyzedMoves.add(
                                AnalyzedMove(
                                    gameId = gameId,
                                    moveIndex = index,
                                    moveNumber = moveNumber,
                                    color = movingColor,
                                    quality = quality,
                                    san = move.san,
                                    bestMove = if (quality.isBad() && bestMoveSan != move.san) bestMoveSan else null,
                                    evalBefore = evalBefore,
                                    evalAfter = evalAfter,
                                    evalLoss = cpLoss,
                                    comment = generateComment(quality, cpLoss)
                                )
                            )
                        }
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Error analyzing move $moveNumber: ${e.message}")
                    evaluations.add(evaluations.lastOrNull() ?: 0)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Critical error: ${e.message}", e)
            throw e
        }

        Log.i(TAG, "Analysis finished: ${analyzedMoves.size} classified moves, ${evaluations.size} evaluations")

        return AnalysisData(analyzedMoves, evaluations, fenPositions)
    }

    // ==================== КЛАССИФИКАЦИЯ ХОДА ====================

    private fun classifyMove(
        evalBefore: Int,
        evalAfter: Int,
        playerColor: ChessColor,
        moveNumber: Int
    ): MoveQuality? {
        val cpLoss = calculateLoss(evalBefore, evalAfter, playerColor)
        val cpGain = calculateGain(evalBefore, evalAfter, playerColor)
        val playerEvalBefore = if (playerColor == ChessColor.WHITE) evalBefore else -evalBefore
        val wasLosing = playerEvalBefore < LOSING_POSITION

        return when {
            cpLoss >= BLUNDER_THRESHOLD -> MoveQuality.BLUNDER
            cpLoss >= MISTAKE_THRESHOLD -> MoveQuality.MISTAKE
            cpLoss >= INACCURACY_THRESHOLD -> MoveQuality.INACCURACY

            moveNumber <= BOOK_MOVES_LIMIT -> {
                when {
                    cpLoss <= EXCELLENT_MAX_LOSS -> MoveQuality.BOOK
                    else -> null
                }
            }

            cpGain >= BRILLIANT_THRESHOLD && wasLosing -> MoveQuality.BRILLIANT
            cpGain >= GREAT_MOVE_THRESHOLD -> MoveQuality.GREAT_MOVE
            cpLoss <= BEST_MOVE_MAX_LOSS -> MoveQuality.BEST_MOVE
            cpLoss <= EXCELLENT_MAX_LOSS -> MoveQuality.EXCELLENT
            cpLoss <= GOOD_MAX_LOSS -> MoveQuality.GOOD

            else -> null
        }
    }

    private fun calculateLoss(evalBefore: Int, evalAfter: Int, playerColor: ChessColor): Int {
        val loss = if (playerColor == ChessColor.WHITE) {
            evalBefore - evalAfter
        } else {
            evalAfter - evalBefore
        }
        return loss.coerceAtLeast(0)
    }

    private fun calculateGain(evalBefore: Int, evalAfter: Int, playerColor: ChessColor): Int {
        val gain = if (playerColor == ChessColor.WHITE) {
            evalAfter - evalBefore
        } else {
            evalBefore - evalAfter
        }
        return gain.coerceAtLeast(0)
    }

    // ==================== CAPS ACCURACY ====================

    private fun centipawnsToWinProbability(cp: Int): Double {
        val cappedCp = cp.coerceIn(-1000, 1000)
        return 50.0 + 50.0 * (2.0 / (1.0 + exp(-0.00368208 * cappedCp)) - 1.0)
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

    private fun calculateCapsAccuracy(evaluations: List<Int>, playerColor: ChessColor): Double {
        if (evaluations.size < 2) return 100.0

        var totalAccuracy = 0.0
        var playerMoveCount = 0

        for (i in 1 until evaluations.size) {
            val moveIndex = i - 1
            val isWhiteMove = moveIndex % 2 == 0
            val isPlayerMove = (playerColor == ChessColor.WHITE && isWhiteMove) ||
                    (playerColor == ChessColor.BLACK && !isWhiteMove)

            if (isPlayerMove) {
                val cpBefore = evaluations[i - 1]
                val cpAfter = evaluations[i]
                totalAccuracy += calculateMoveAccuracy(cpBefore, cpAfter, playerColor)
                playerMoveCount++
            }
        }

        return if (playerMoveCount > 0) totalAccuracy / playerMoveCount else 100.0
    }

    private fun calculateAverageLoss(evaluations: List<Int>, playerColor: ChessColor): Int {
        if (evaluations.size < 2) return 0

        var totalLoss = 0
        var count = 0

        for (i in 1 until evaluations.size) {
            val moveIndex = i - 1
            val isWhiteMove = moveIndex % 2 == 0
            val isPlayerMove = (playerColor == ChessColor.WHITE && isWhiteMove) ||
                    (playerColor == ChessColor.BLACK && !isWhiteMove)

            if (isPlayerMove) {
                totalLoss += calculateLoss(evaluations[i - 1], evaluations[i], playerColor)
                count++
            }
        }

        return if (count > 0) totalLoss / count else 0
    }

    // ==================== УТИЛИТЫ ====================

    private fun normalizePgn(pgn: String): String {
        val trimmed = pgn.trim()

        Log.d(TAG, "Original PGN (first 200 chars): ${trimmed.take(200)}")

        if (trimmed.isEmpty()) {
            throw IllegalArgumentException("PGN пустой")
        }

        val movesOnly = trimmed
            .replace(Regex("""\[[^\]]*\]"""), " ")
            .replace(Regex("""\{[^}]*\}"""), " ")
            .replace(Regex("""\([^)]*\)"""), " ")
            .replace(Regex("""\$\d+"""), " ")
            .replace(Regex("""(1-0|0-1|1/2-1/2|\*)\s*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

        Log.d(TAG, "Moves only: $movesOnly")

        if (movesOnly.isEmpty()) {
            throw IllegalArgumentException("В PGN нет ходов")
        }

        val result = buildString {
            appendLine("""[Event "Imported Game"]""")
            appendLine("""[Site "ChessMentor"]""")
            appendLine("""[Date "????.??.??"]""")
            appendLine("""[Round "1"]""")
            appendLine("""[White "Player"]""")
            appendLine("""[Black "Opponent"]""")
            appendLine("""[Result "*"]""")
            appendLine()
            append(movesOnly)
            append(" *")
            appendLine()
        }

        Log.d(TAG, "Normalized PGN (first 300 chars): ${result.take(300)}")

        return result
    }

    private fun convertUciToSan(uciMove: String, fen: String): String {
        return try {
            val tempBoard = Board()
            tempBoard.loadFromFen(fen)
            val move = com.github.bhlangonijr.chesslib.move.Move(uciMove, tempBoard.sideToMove)
            tempBoard.doMove(move)
            move.san ?: uciMove
        } catch (e: Exception) {
            uciMove
        }
    }

    private fun detectThemeId(move: AnalyzedMove): Long {
        return when {
            move.san.contains("x") -> 2L
            move.moveNumber < 10 -> 1L
            move.moveNumber > 40 -> 4L
            else -> 3L
        }
    }

    private fun generateComment(quality: MoveQuality, cpLoss: Int): String {
        val pawns = abs(cpLoss) / 100.0
        return when (quality) {
            MoveQuality.BRILLIANT -> "Блестящий ход! Отличная находка."
            MoveQuality.GREAT_MOVE -> "Отличный ход!"
            MoveQuality.BEST_MOVE -> "Лучший ход."
            MoveQuality.EXCELLENT -> "Превосходно."
            MoveQuality.GOOD -> "Хороший ход."
            MoveQuality.BOOK -> "Теоретический ход."
            MoveQuality.INACCURACY -> "Неточность (−%.1f).".format(pawns)
            MoveQuality.MISTAKE -> "Ошибка (−%.1f).".format(pawns)
            MoveQuality.BLUNDER -> "Грубый зевок! (−%.1f)".format(pawns)
            MoveQuality.MISSED_WIN -> "Упущена победа!"
        }
    }
}