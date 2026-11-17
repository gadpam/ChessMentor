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

        // Прогреваем движок в фоне только при первом запуске Activity (init() идемпотентен)
        if (savedInstanceState == null) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val initialized = container.chessEngine.init()
                    Log.i("MainActivity", "Chess engine initialized: $initialized")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed to init engine", e)
                }
            }
        }

        setContent {
            ChessMentorTheme {
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
