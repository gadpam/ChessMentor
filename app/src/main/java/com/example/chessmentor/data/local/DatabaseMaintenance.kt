// data/local/DatabaseMaintenance.kt
package com.example.chessmentor.data.local

import android.util.Log
import com.example.chessmentor.data.local.dao.ExerciseDao
import com.example.chessmentor.data.local.dao.GameDao
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Фоновая проверка и самовосстановление согласованности базы данных при холодном старте:
 * 1. Восстанавливает партии, застрявшие в статусе `IN_PROGRESS` после завершения процесса ОС
 *    (если есть сохранённые `accuracy` и `evaluationsJson` — переводит в `COMPLETED`, иначе в `PENDING`,
 *    чтобы пользователь мог запустить чистый повторный анализ).
 * 2. Удаляет осиротевшие персональные задачи (`exercises.sourceGameId` указывает на удалённую партию).
 * 3. Удаляет случайные дубликаты во встроенном глобальном банке задач.
 */
class DatabaseMaintenance(
    private val gameDao: GameDao,
    private val exerciseDao: ExerciseDao
) {
    companion object {
        private const val TAG = "DatabaseMaintenance"
    }

    data class MaintenanceReport(
        val recoveredCompletedGames: Int = 0,
        val resetPendingGames: Int = 0,
        val deletedOrphanExercises: Int = 0
    )

    private val hasRun = AtomicBoolean(false)

    suspend fun runStartupChecksOnce(): MaintenanceReport = withContext(Dispatchers.IO) {
        if (!hasRun.compareAndSet(false, true)) {
            return@withContext MaintenanceReport()
        }
        try {
            val recoveredCompleted = gameDao.markFinishedInterruptedGamesCompleted()
            val resetPending = gameDao.resetInterruptedGamesToPending()
            val deletedOrphans = exerciseDao.deleteOrphanedGameExercises()
            exerciseDao.deleteGlobalDuplicates()

            if (recoveredCompleted > 0 || resetPending > 0 || deletedOrphans > 0) {
                Log.i(
                    TAG,
                    "Startup DB recovery: completed=$recoveredCompleted, resetToPending=$resetPending, orphanExercises=$deletedOrphans"
                )
            }
            MaintenanceReport(
                recoveredCompletedGames = recoveredCompleted,
                resetPendingGames = resetPending,
                deletedOrphanExercises = deletedOrphans
            )
        } catch (e: CancellationException) {
            hasRun.set(false)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Startup DB maintenance failed (non-fatal)", e)
            MaintenanceReport()
        }
    }
}
