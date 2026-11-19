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
import androidx.lifecycle.lifecycleScope
import com.example.chessmentor.di.AppContainer
import com.example.chessmentor.presentation.ui.components.navigation.ChessMentorApp
import com.example.chessmentor.presentation.ui.components.theme.ChessMentorTheme
import com.example.chessmentor.presentation.viewmodel.ChessMentorViewModelFactory
import com.example.chessmentor.presentation.viewmodel.GameViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var container: AppContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        container = AppContainer.getInstance(applicationContext)
        val factory = ChessMentorViewModelFactory(container)
        val gameViewModel = ViewModelProvider(this, factory)[GameViewModel::class.java]

        Log.i("CPU_ARCH", "ABI: ${Build.SUPPORTED_ABIS.joinToString()}")

        // Восстанавливаем сохранённую сессию и проверяем целостность БД при холодном старте
        gameViewModel.restoreSessionIfNeeded()

        // Прогреваем Stockfish и проверяем/скачиваем NNUE в фоне (статус транслируется в UI)
        if (savedInstanceState == null) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val initialized = container.chessEngine.init()
                    Log.i("MainActivity", "Chess engine initialized: $initialized")
                } catch (e: CancellationException) {
                    Log.i("MainActivity", "Chess engine startup init cancelled")
                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed to init engine", e)
                }
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

    override fun onDestroy() {
        super.onDestroy()
        // Не уничтожаем Stockfish при повороте экрана / смене конфигурации
        if (isFinishing && !isChangingConfigurations && ::container.isInitialized) {
            container.cleanup()
        }
    }
}
