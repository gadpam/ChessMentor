// di/AppContainer.kt
package com.example.chessmentor.di

import android.content.Context
import com.example.chessmentor.data.engine.ChessEngine
import com.example.chessmentor.data.engine.StockfishEngine
import com.example.chessmentor.data.local.AppDatabase
import com.example.chessmentor.data.local.DatabaseMaintenance
import com.example.chessmentor.data.repository.SharedPrefsEngineSettingsRepository
import com.example.chessmentor.data.repository.SharedPrefsSessionRepository
import com.example.chessmentor.data.repository.room.RoomAnalyzedMoveRepository
import com.example.chessmentor.data.repository.room.RoomExerciseRepository
import com.example.chessmentor.data.repository.room.RoomGameRepository
import com.example.chessmentor.data.repository.room.RoomMistakeRepository
import com.example.chessmentor.data.repository.room.RoomUserRepository
import com.example.chessmentor.domain.analysis.SemanticMistakeAnalyzer
import com.example.chessmentor.domain.repository.AnalyzedMoveRepository
import com.example.chessmentor.domain.repository.EngineSettingsRepository
import com.example.chessmentor.domain.repository.ExerciseRepository
import com.example.chessmentor.domain.repository.GameRepository
import com.example.chessmentor.domain.repository.MistakeRepository
import com.example.chessmentor.domain.repository.SessionRepository
import com.example.chessmentor.domain.repository.UserRepository
import com.example.chessmentor.domain.usecase.AnalyzeGameUseCase
import com.example.chessmentor.domain.usecase.ChangePasswordUseCase
import com.example.chessmentor.domain.usecase.GenerateExercisesFromMistakesUseCase
import com.example.chessmentor.domain.usecase.GetTrainingExerciseUseCase
import com.example.chessmentor.domain.usecase.GetUserStatisticsUseCase
import com.example.chessmentor.domain.usecase.LoginUserUseCase
import com.example.chessmentor.domain.usecase.RegisterUserUseCase
import com.example.chessmentor.domain.usecase.UploadGameUseCase

class AppContainer private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: AppContainer? = null

        fun getInstance(context: Context): AppContainer {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppContainer(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // База данных
    private val database: AppDatabase by lazy {
        AppDatabase.getInstance(context)
    }

    // Пользовательская сессия и общие настройки приложения
    val sessionRepository: SessionRepository by lazy {
        SharedPrefsSessionRepository(context)
    }

    // Настройки движка
    val engineSettingsRepository: EngineSettingsRepository by lazy {
        SharedPrefsEngineSettingsRepository(context)
    }

    // Движок — локальный Stockfish с реактивным статусом загрузки NNUE
    val chessEngine: ChessEngine by lazy {
        StockfishEngine(
            context = context,
            engineSettingsRepository = engineSettingsRepository
        )
    }

    // DAO
    private val userDao by lazy { database.userDao() }
    private val gameDao by lazy { database.gameDao() }
    private val mistakeDao by lazy { database.mistakeDao() }
    private val exerciseDao by lazy { database.exerciseDao() }
    private val analyzedMoveDao by lazy { database.analyzedMoveDao() }

    // Фоновое обслуживание и самовосстановление согласованности БД
    val databaseMaintenance: DatabaseMaintenance by lazy {
        DatabaseMaintenance(
            gameDao = gameDao,
            exerciseDao = exerciseDao
        )
    }

    // Семантический анализатор ошибок
    val semanticMistakeAnalyzer by lazy {
        SemanticMistakeAnalyzer(chessEngine)
    }

    // Репозитории
    val userRepository: UserRepository by lazy {
        RoomUserRepository(userDao)
    }

    val gameRepository: GameRepository by lazy {
        RoomGameRepository(gameDao)
    }

    val mistakeRepository: MistakeRepository by lazy {
        RoomMistakeRepository(mistakeDao)
    }

    val exerciseRepository: ExerciseRepository by lazy {
        RoomExerciseRepository(exerciseDao)
    }

    val analyzedMoveRepository: AnalyzedMoveRepository by lazy {
        RoomAnalyzedMoveRepository(analyzedMoveDao)
    }

    // Use Cases
    val registerUserUseCase by lazy {
        RegisterUserUseCase(userRepository)
    }

    val loginUserUseCase by lazy {
        LoginUserUseCase(userRepository)
    }

    val changePasswordUseCase by lazy {
        ChangePasswordUseCase(userRepository)
    }

    val uploadGameUseCase by lazy {
        UploadGameUseCase(
            gameRepository = gameRepository,
            userRepository = userRepository
        )
    }

    val analyzeGameUseCase by lazy {
        AnalyzeGameUseCase(
            gameRepository = gameRepository,
            mistakeRepository = mistakeRepository,
            analyzedMoveRepository = analyzedMoveRepository,
            userRepository = userRepository,
            chessEngine = chessEngine,
            engineSettingsRepository = engineSettingsRepository
        )
    }

    val getTrainingExerciseUseCase by lazy {
        GetTrainingExerciseUseCase(
            userRepository = userRepository,
            mistakeRepository = mistakeRepository,
            exerciseRepository = exerciseRepository
        )
    }

    val generateExercisesFromMistakesUseCase by lazy {
        GenerateExercisesFromMistakesUseCase(
            mistakeRepository = mistakeRepository,
            analyzedMoveRepository = analyzedMoveRepository,
            exerciseRepository = exerciseRepository,
            gameRepository = gameRepository,
            chessEngine = chessEngine
        )
    }

    val getUserStatisticsUseCase by lazy {
        GetUserStatisticsUseCase(
            userRepository = userRepository,
            gameRepository = gameRepository,
            mistakeRepository = mistakeRepository,
            exerciseRepository = exerciseRepository,
            analyzedMoveRepository = analyzedMoveRepository
        )
    }

    // Очистка ресурсов
    fun cleanup() {
        chessEngine.destroy()
    }
}
