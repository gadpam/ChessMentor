package com.example.chessmentor.data.chesscom

import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.pgn.PgnParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class ArchiveGame(
    val fingerprint: String, val pgn: String, val ended: Long,
    val opponent: String, val side: ChessColor, val outcome: String,
    val libraryId: Long?
) {
    val inLibrary get() = libraryId != null
}

internal class ChessComArchive(private val get: suspend (String) -> String) {
    companion object {
        val nickname = Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,48}[a-zA-Z0-9]|[a-zA-Z0-9]")
        private val month = Regex("[0-9]{4}/(0[1-9]|1[0-2])")
    }

    private fun base(user: String): String {
        require(nickname.matches(user)) { "Проверьте ник Chess.com" }
        return "https://api.chess.com/pub/player/${user.lowercase(Locale.ROOT)}/games/"
    }

    suspend fun months(user: String): List<String> = withContext(Dispatchers.IO) {
        val prefix = base(user)
        val items = JSONObject(get(prefix + "archives")).getJSONArray("archives")
        (0 until items.length()).map { items.getString(it).lowercase(Locale.ROOT).removePrefix(prefix) }
            .filter(month::matches).distinct().sortedDescending()
    }

    suspend fun latest(user: String, period: String, known: Map<String, Long>): List<ArchiveGame> = withContext(Dispatchers.IO) {
        require(month.matches(period)) { "Неверный месяц архива" }
        val items = JSONObject(get(base(user) + period)).getJSONArray("games")
        (0 until items.length()).asSequence().mapNotNull { items.optJSONObject(it) }
            .filter { it.optString("rules") == "chess" && it.optLong("end_time") in 1..253402300799L }
            .sortedByDescending { it.optLong("end_time") }
            .mapNotNull { game ->
                val pgn = game.optString("pgn")
                if (pgn.isBlank() || pgn.length > PgnParser.MAX_SINGLE_PGN_CHARS) return@mapNotNull null
                val parsed = (PgnParser.parseAndValidate(pgn) as? PgnParser.ParseResult.Success)?.game ?: return@mapNotNull null
                val meta = parsed.metadata
                if (meta.result == PgnParser.PgnResult.ONGOING) return@mapNotNull null
                val side = when {
                    user.equals(meta.whiteName, true) -> ChessColor.WHITE
                    user.equals(meta.blackName, true) -> ChessColor.BLACK
                    else -> return@mapNotNull null
                }
                ArchiveGame(parsed.fingerprint, pgn, game.getLong("end_time"),
                    (if (side == ChessColor.WHITE) meta.blackName else meta.whiteName) ?: "Без имени",
                    side, meta.result.forPlayer(side).displayName, known[parsed.fingerprint])
            }.distinctBy { it.fingerprint }.take(10).toList()
    }
}

/** One cached, read-only HTTP client. OkHttp handles cache freshness and conditional GET/304. */
internal class ChessComHttp(cacheDir: File) {
    private val client = OkHttpClient.Builder()
        .cache(Cache(File(cacheDir, "chesscom-public"), 12L * 1024 * 1024))
        .followRedirects(false).retryOnConnectionFailure(false)
        .callTimeout(25, TimeUnit.SECONDS).build()
    @Volatile private var retryAfter = 0L

    suspend fun get(url: String): String = suspendCancellableCoroutine { cont ->
        if (System.currentTimeMillis() < retryAfter) {
            cont.resumeWith(Result.failure(IOException("Слишком много запросов. Попробуйте позже.")))
            return@suspendCancellableCoroutine
        }
        val call = client.newCall(Request.Builder().url(url)
            .header("User-Agent", "ChessMentor-Android (https://github.com/gadpam/ChessMentor)")
            .header("Accept", "application/json").build())
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWith(Result.failure(IOException("Не удалось связаться с Chess.com. Проверьте подключение.", e)))
            }
            override fun onResponse(call: Call, response: Response) {
                cont.resumeWith(runCatching { response.use {
                    when (it.code) {
                        200 -> Unit
                        404 -> throw IOException("Ник или архив месяца не найден.")
                        410 -> throw IOException("Этот архив больше недоступен.")
                        429 -> {
                            val seconds = it.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 86400) ?: 60
                            retryAfter = System.currentTimeMillis() + seconds * 1000
                            throw IOException("Слишком много запросов. Попробуйте позже.")
                        }
                        else -> throw IOException("Chess.com не вернул архив (HTTP ${it.code}).")
                    }
                    val source = it.body?.source() ?: throw IOException("Пустой ответ Chess.com.")
                    if (source.request(4L * 1024 * 1024 + 1)) throw IOException("Архив слишком велик. Выберите другой месяц или загрузите PGN-файл.")
                    source.readUtf8()
                } })
            }
        })
    }
}
