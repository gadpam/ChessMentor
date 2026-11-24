// MainActivity.kt
package com.example.chessmentor

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.example.chessmentor.di.AppContainer
import com.example.chessmentor.di.DebugEnvironment
import com.example.chessmentor.presentation.ui.components.navigation.ChessMentorApp
import com.example.chessmentor.presentation.ui.components.theme.ChessMentorTheme
import com.example.chessmentor.presentation.viewmodel.ChessMentorViewModelFactory
import com.example.chessmentor.presentation.viewmodel.GameViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        DebugEnvironment.configureStrictMode()
        super.onCreate(savedInstanceState)

        val container = AppContainer.getInstance(applicationContext)
        val factory = ChessMentorViewModelFactory(container)
        val gameViewModel = ViewModelProvider(this, factory)[GameViewModel::class.java]

        Log.i("CPU_ARCH", "ABI: ${Build.SUPPORTED_ABIS.joinToString()}")

        // Восстанавливаем сохранённую сессию и проверяем целостность БД при холодном старте
        gameViewModel.restoreSessionIfNeeded()

        // Прогреваем Stockfish и проверяем/скачиваем NNUE в фоне (статус транслируется в UI).
        //
        // Работа запускается в scope приложения, а не в lifecycleScope: загрузка ~100 МБ
        // не должна отменяться при повороте экрана или уходе в фон (NnueDownloader удаляет
        // недокачанный .part-файл при отмене). ensureReadyOnStartup() вызывается и при
        // повторном создании Activity, но не перезапускает уже идущую загрузку.
        container.appScope.launch {
            try {
                val initialized = container.chessEngine.ensureReadyOnStartup()
                Log.i("MainActivity", "Chess engine startup init: $initialized")
            } catch (e: CancellationException) {
                Log.i("MainActivity", "Chess engine startup init cancelled")
                throw e
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to init engine", e)
            }
        }

        setContent {
            val themeMode by container.sessionRepository.themeMode.collectAsState()
            ChessMentorTheme(themeMode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ChessMentorApp(container, gameViewModel)
                }
            }
        }
    }
}
