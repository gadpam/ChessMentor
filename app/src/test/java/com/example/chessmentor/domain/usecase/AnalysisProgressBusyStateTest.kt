package com.example.chessmentor.domain.usecase

import com.example.chessmentor.domain.entity.AnalysisStatus
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Game
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Регрессия на реальную ошибку: завершённый или упавший разбор оставлял прогресс в потоке,
 * и экран просмотра партии считал движок занятым — live-анализ и проверка альтернативного
 * хода блокировались до перезапуска приложения.
 */
class AnalysisProgressBusyStateTest {

    private val game = Game(
        id = 1L,
        userId = 1L,
        pgnData = "1. e4 e5",
        playerColor = ChessColor.WHITE,
        playedAt = 0L,
        analysisStatus = AnalysisStatus.COMPLETED
    )

    @Test
    fun `no progress means engine is free`() {
        assertFalse(AnalyzeGameUseCase.AnalysisProgress.isEngineBusy(null))
    }

    @Test
    fun `active phases keep engine busy`() {
        assertTrue(AnalyzeGameUseCase.AnalysisProgress.isEngineBusy(
            AnalyzeGameUseCase.AnalysisProgress.Starting
        ))
        assertTrue(AnalyzeGameUseCase.AnalysisProgress.isEngineBusy(
            AnalyzeGameUseCase.AnalysisProgress.InProgress(currentMove = 3, totalMoves = 40)
        ))
    }

    @Test
    fun `terminal phases release engine`() {
        val success = AnalyzeGameUseCase.Result.Success(
            game = game,
            analyzedMoves = emptyList(),
            mistakes = emptyList(),
            evaluations = listOf(20, 15)
        )
        assertFalse(AnalyzeGameUseCase.AnalysisProgress.isEngineBusy(
            AnalyzeGameUseCase.AnalysisProgress.Completed(success)
        ))
        assertFalse(AnalyzeGameUseCase.AnalysisProgress.isEngineBusy(
            AnalyzeGameUseCase.AnalysisProgress.Completed(AnalyzeGameUseCase.Result.Error("нет сети"))
        ))
        assertFalse(AnalyzeGameUseCase.AnalysisProgress.isEngineBusy(
            AnalyzeGameUseCase.AnalysisProgress.Failed("движок не вернул оценку")
        ))
    }
}
