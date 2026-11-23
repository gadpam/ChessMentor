// domain/backup/UserProgressBackupCodec.kt
package com.example.chessmentor.domain.backup

import com.example.chessmentor.domain.backup.SimpleJson.Value
import com.example.chessmentor.domain.entity.AnalysisStatus
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.EngineSettings
import com.example.chessmentor.domain.entity.ExerciseSource
import com.example.chessmentor.domain.entity.MistakeType
import com.example.chessmentor.domain.entity.MoveQuality
import com.example.chessmentor.domain.entity.TacticalPattern
import com.example.chessmentor.domain.entity.TacticalTheme
import com.example.chessmentor.domain.session.AppThemeMode
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Кодек полного резервного снимка прогресса пользователя ChessMentor (формат JSON + SHA-256).
 *
 * Содержит:
 * - профиль и предпочтения (без пароля и без хеша пароля);
 * - настройки темы оформления, никнейм на Lichess/Chess.com и параметры Stockfish;
 * - все партии с результатами анализа, оценками по полуходам, классификацией ходов и ошибками;
 * - персональные тактические упражнения (с привязкой к исходной партии);
 * - всю историю попыток решения задач (включая метаданные SM-2/Лейтнера, подсказок и времени).
 */
object UserProgressBackupCodec {

    const val FORMAT_ID = "chessmentor.user_backup"
    const val CURRENT_SCHEMA_VERSION = 1

    private val FILE_DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val SLUG_SANITIZE_REGEX = Regex("""[^a-z0-9_]+""")

    // ==================== МОДЕЛИ СНИМКА ====================

    data class ProfileSection(
        val nickname: String,
        val rating: Int,
        val isSoundEnabled: Boolean,
        val preferredBoardTheme: String,
        val appThemeMode: AppThemeMode,
        val chessUsernameHint: String? = null,
        val engineSettings: EngineSettings? = null
    )

    data class AnalyzedMoveEntry(
        val moveIndex: Int,
        val moveNumber: Int,
        val color: ChessColor,
        val quality: MoveQuality,
        val san: String,
        val bestMove: String? = null,
        val evalBefore: Int,
        val evalAfter: Int,
        val evalLoss: Int,
        val comment: String? = null
    )

    data class MistakeEntry(
        val themeId: Long,
        val moveNumber: Int,
        val color: ChessColor,
        val evaluationLoss: Int,
        val mistakeType: MistakeType,
        val bestMove: String,
        val userMove: String,
        val comment: String? = null,
        val fenBefore: String? = null
    )

    data class GameEntry(
        val pgnData: String,
        val playerColor: ChessColor,
        val timeControl: String? = null,
        val playedAt: Long,
        val analysisStatus: AnalysisStatus = AnalysisStatus.PENDING,
        val analyzedAt: Long? = null,
        val accuracy: Double? = null,
        val averageEvaluationLoss: Int? = null,
        val totalMistakes: Int = 0,
        val blundersCount: Int = 0,
        val mistakesCount: Int = 0,
        val inaccuraciesCount: Int = 0,
        val evaluationsJson: String? = null,
        val analyzedMoves: List<AnalyzedMoveEntry> = emptyList(),
        val mistakes: List<MistakeEntry> = emptyList()
    )

    data class PersonalExerciseEntry(
        val fenPosition: String,
        val solutionMoves: List<String>,
        val tacticalPattern: TacticalPattern,
        val patternClusterId: String,
        val difficultyRating: Int,
        val patternFeatures: List<String> = emptyList(),
        val theme: TacticalTheme = TacticalTheme.TACTIC,
        val rating: Int = 1500,
        val createdFrom: ExerciseSource = ExerciseSource.MISTAKE,
        /** Индекс партии в массиве [BackupSnapshot.games], из которой создана задача (`null`, если партия удалена). */
        val sourceGameIndex: Int? = null,
        val sourceMoveNumber: Int? = null,
        val createdAt: Long,
        val timesShown: Int = 0,
        val timesSolved: Int = 0,
        val successRate: Int = 0
    )

    data class AttemptEntry(
        /** Индекс в [BackupSnapshot.personalExercises], если попытка относится к персональной задаче. */
        val personalExerciseIndex: Int? = null,
        /** Стабильный идентификатор задачи встроенного банка (`CURATED_ID:...`), если есть. */
        val curatedId: String? = null,
        /** FEN задачи как универсальный запасной ключ сопоставления. */
        val exerciseFen: String? = null,
        val solved: Boolean,
        val timeSpentSeconds: Int,
        val movesMade: List<String>,
        val attemptedAt: Long
    )

    data class BackupSnapshot(
        val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
        val exportedAtMs: Long,
        val profile: ProfileSection,
        val games: List<GameEntry> = emptyList(),
        val personalExercises: List<PersonalExerciseEntry> = emptyList(),
        val attempts: List<AttemptEntry> = emptyList()
    )

    sealed class DecodeResult {
        data class Success(val snapshot: BackupSnapshot) : DecodeResult()
        data class Error(val message: String) : DecodeResult()
    }

    // ==================== КОДИРОВАНИЕ И ПРОВЕРКА ====================

    fun encode(snapshot: BackupSnapshot, pretty: Boolean = true): String {
        val payload = buildPayloadObject(snapshot)
        val canonicalPayload = SimpleJson.stringify(payload, pretty = false)
        val checksum = sha256Hex(canonicalPayload)

        val envelope = SimpleJson.obj(
            "format" to SimpleJson.str(FORMAT_ID),
            "schemaVersion" to SimpleJson.num(snapshot.schemaVersion),
            "exportedAtMs" to SimpleJson.num(snapshot.exportedAtMs),
            "checksumSha256" to SimpleJson.str(checksum),
            "payload" to payload
        )
        return SimpleJson.stringify(envelope, pretty = pretty)
    }

    fun decodeAndVerify(rawJson: String): DecodeResult {
        if (rawJson.isBlank()) {
            return DecodeResult.Error("Файл резервной копии пуст")
        }
        return try {
            val root = SimpleJson.parseObject(rawJson)
            val format = root.stringOrNull("format")
            if (format != FORMAT_ID) {
                return DecodeResult.Error("Выбранный файл не является резервной копией ChessMentor")
            }

            val version = root.intOrNull("schemaVersion")
                ?: return DecodeResult.Error("Не указана версия формата резервной копии")
            if (version < 1 || version > CURRENT_SCHEMA_VERSION) {
                return DecodeResult.Error(
                    "Версия резервной копии ($version) не поддерживается этой версией приложения"
                )
            }

            val exportedAt = root.longOrNull("exportedAtMs")?.coerceAtLeast(0L) ?: 0L
            val expectedChecksum = root.stringOrNull("checksumSha256")?.lowercase(Locale.ROOT)
                ?: return DecodeResult.Error("Отсутствует контрольная сумма резервной копии")
            val payload = root.objOrNull("payload")
                ?: return DecodeResult.Error("Отсутствует секция данных (payload) в резервной копии")

            val actualChecksum = sha256Hex(SimpleJson.stringify(payload, pretty = false))
            if (!MessageDigest.isEqual(
                    actualChecksum.toByteArray(Charsets.UTF_8),
                    expectedChecksum.toByteArray(Charsets.UTF_8)
                )
            ) {
                return DecodeResult.Error(
                    "Контрольная сумма SHA-256 не совпадает — файл повреждён или изменён"
                )
            }

            val snapshot = parsePayload(version, exportedAt, payload)
            DecodeResult.Success(snapshot)
        } catch (e: SimpleJson.JsonFormatException) {
            DecodeResult.Error("Ошибка чтения JSON: ${e.message}")
        } catch (e: IllegalArgumentException) {
            DecodeResult.Error("Некорректные данные в копии: ${e.message}")
        }
    }

    fun suggestFileName(
        nickname: String,
        nowMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        val slug = nickname
            .trim()
            .lowercase(Locale.ROOT)
            .replace(SLUG_SANITIZE_REGEX, "_")
            .trim('_')
            .take(24)
            .ifEmpty { "player" }
        val date = Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate().format(FILE_DATE_FMT)
        return "chessmentor_backup_${slug}_${date}.json"
    }

    // ==================== ВНУТРЕННЕЕ ПРЕОБРАЗОВАНИЕ JSON ====================

    private fun buildPayloadObject(snapshot: BackupSnapshot): Value.Obj {
        val p = snapshot.profile
        val profileObj = SimpleJson.obj(
            "nickname" to SimpleJson.str(p.nickname),
            "rating" to SimpleJson.num(p.rating),
            "isSoundEnabled" to SimpleJson.bool(p.isSoundEnabled),
            "preferredBoardTheme" to SimpleJson.str(p.preferredBoardTheme),
            "appThemeMode" to SimpleJson.str(p.appThemeMode.storageKey),
            "chessUsernameHint" to SimpleJson.str(p.chessUsernameHint),
            "engineSettings" to (p.engineSettings?.let { es ->
                SimpleJson.obj(
                    "depth" to SimpleJson.num(es.depth),
                    "threads" to SimpleJson.num(es.threads),
                    "hashSizeMb" to SimpleJson.num(es.hashSizeMb)
                )
            } ?: Value.Null)
        )

        val gamesArr = SimpleJson.arr(snapshot.games.map { g ->
            SimpleJson.obj(
                "pgnData" to SimpleJson.str(g.pgnData),
                "playerColor" to SimpleJson.str(g.playerColor.name),
                "timeControl" to SimpleJson.str(g.timeControl),
                "playedAt" to SimpleJson.num(g.playedAt),
                "analysisStatus" to SimpleJson.str(g.analysisStatus.name),
                "analyzedAt" to SimpleJson.num(g.analyzedAt),
                "accuracy" to SimpleJson.num(g.accuracy),
                "averageEvaluationLoss" to SimpleJson.num(g.averageEvaluationLoss),
                "totalMistakes" to SimpleJson.num(g.totalMistakes),
                "blundersCount" to SimpleJson.num(g.blundersCount),
                "mistakesCount" to SimpleJson.num(g.mistakesCount),
                "inaccuraciesCount" to SimpleJson.num(g.inaccuraciesCount),
                "evaluationsJson" to SimpleJson.str(g.evaluationsJson),
                "analyzedMoves" to SimpleJson.arr(g.analyzedMoves.map { m ->
                    SimpleJson.obj(
                        "moveIndex" to SimpleJson.num(m.moveIndex),
                        "moveNumber" to SimpleJson.num(m.moveNumber),
                        "color" to SimpleJson.str(m.color.name),
                        "quality" to SimpleJson.str(m.quality.name),
                        "san" to SimpleJson.str(m.san),
                        "bestMove" to SimpleJson.str(m.bestMove),
                        "evalBefore" to SimpleJson.num(m.evalBefore),
                        "evalAfter" to SimpleJson.num(m.evalAfter),
                        "evalLoss" to SimpleJson.num(m.evalLoss),
                        "comment" to SimpleJson.str(m.comment)
                    )
                }),
                "mistakes" to SimpleJson.arr(g.mistakes.map { mk ->
                    SimpleJson.obj(
                        "themeId" to SimpleJson.num(mk.themeId),
                        "moveNumber" to SimpleJson.num(mk.moveNumber),
                        "color" to SimpleJson.str(mk.color.name),
                        "evaluationLoss" to SimpleJson.num(mk.evaluationLoss),
                        "mistakeType" to SimpleJson.str(mk.mistakeType.name),
                        "bestMove" to SimpleJson.str(mk.bestMove),
                        "userMove" to SimpleJson.str(mk.userMove),
                        "comment" to SimpleJson.str(mk.comment),
                        "fenBefore" to SimpleJson.str(mk.fenBefore)
                    )
                })
            )
        })

        val exercisesArr = SimpleJson.arr(snapshot.personalExercises.map { ex ->
            SimpleJson.obj(
                "fenPosition" to SimpleJson.str(ex.fenPosition),
                "solutionMoves" to SimpleJson.arr(ex.solutionMoves.map { SimpleJson.str(it) }),
                "tacticalPattern" to SimpleJson.str(ex.tacticalPattern.name),
                "patternClusterId" to SimpleJson.str(ex.patternClusterId),
                "difficultyRating" to SimpleJson.num(ex.difficultyRating),
                "patternFeatures" to SimpleJson.arr(ex.patternFeatures.map { SimpleJson.str(it) }),
                "theme" to SimpleJson.str(ex.theme.name),
                "rating" to SimpleJson.num(ex.rating),
                "createdFrom" to SimpleJson.str(ex.createdFrom.name),
                "sourceGameIndex" to SimpleJson.num(ex.sourceGameIndex),
                "sourceMoveNumber" to SimpleJson.num(ex.sourceMoveNumber),
                "createdAt" to SimpleJson.num(ex.createdAt),
                "timesShown" to SimpleJson.num(ex.timesShown),
                "timesSolved" to SimpleJson.num(ex.timesSolved),
                "successRate" to SimpleJson.num(ex.successRate)
            )
        })

        val attemptsArr = SimpleJson.arr(snapshot.attempts.map { a ->
            SimpleJson.obj(
                "personalExerciseIndex" to SimpleJson.num(a.personalExerciseIndex),
                "curatedId" to SimpleJson.str(a.curatedId),
                "exerciseFen" to SimpleJson.str(a.exerciseFen),
                "solved" to SimpleJson.bool(a.solved),
                "timeSpentSeconds" to SimpleJson.num(a.timeSpentSeconds),
                "movesMade" to SimpleJson.arr(a.movesMade.map { SimpleJson.str(it) }),
                "attemptedAt" to SimpleJson.num(a.attemptedAt)
            )
        })

        return SimpleJson.obj(
            "profile" to profileObj,
            "games" to gamesArr,
            "personalExercises" to exercisesArr,
            "attempts" to attemptsArr
        )
    }

    // Индексы в копии ссылаются на позиции в массивах, поэтому нельзя пропускать
    // повреждённые записи: это перепривяжет задачи и попытки к чужим строкам.
    private fun Value.Obj.array(key: String): List<Value> =
        (this[key] as? Value.Arr)?.items ?: throw IllegalArgumentException("Ожидался массив $key")

    private fun Value.Obj.rows(key: String) = array(key).mapIndexed { index, value ->
        value as? Value.Obj ?: throw IllegalArgumentException("Некорректная запись $key[$index]")
    }

    private fun Value.Obj.strings(key: String) = array(key).mapIndexed { index, value ->
        (value as? Value.Str)?.value ?: throw IllegalArgumentException("Ожидалась строка $key[$index]")
    }

    private fun Value.Obj.nonBlankString(key: String) =
        requireString(key).also { require(it.isNotBlank()) { "Пустое поле $key" } }

    private fun Value.Obj.optionalIndex(key: String, indices: IntRange): Int? = when (this[key]) {
        null, Value.Null -> null
        else -> requireInt(key).also { require(it in indices) { "Некорректная ссылка $key: $it" } }
    }

    private fun parsePayload(
        version: Int,
        exportedAtMs: Long,
        payload: Value.Obj
    ): BackupSnapshot {
        val pObj = payload.objOrNull("profile")
            ?: throw IllegalArgumentException("Отсутствует профиль пользователя")

        val profile = ProfileSection(
            nickname = pObj.stringOrNull("nickname")?.trim()?.take(50).orEmpty().ifEmpty { "ChessPlayer" },
            rating = (pObj.intOrNull("rating") ?: 1200).coerceIn(100, 3500),
            isSoundEnabled = pObj.boolOrNull("isSoundEnabled") ?: true,
            preferredBoardTheme = pObj.stringOrNull("preferredBoardTheme")?.trim().orEmpty().ifEmpty { "Классическая" },
            appThemeMode = AppThemeMode.fromStorageKey(pObj.stringOrNull("appThemeMode")),
            chessUsernameHint = pObj.stringOrNull("chessUsernameHint")?.trim()?.takeIf { it.isNotEmpty() },
            engineSettings = pObj.objOrNull("engineSettings")?.let { es ->
                EngineSettings(
                    depth = es.intOrNull("depth") ?: EngineSettings.DEFAULT_DEPTH,
                    threads = es.intOrNull("threads") ?: EngineSettings.DEFAULT_THREADS,
                    hashSizeMb = es.intOrNull("hashSizeMb") ?: EngineSettings.DEFAULT_HASH_SIZE
                ).validate()
            }
        )

        val games = payload.rows("games").map { g ->
            val pgn = g.nonBlankString("pgnData")
            val color = enumOrDefault(g.stringOrNull("playerColor"), ChessColor.WHITE)
            val rawStatus = enumOrDefault(g.stringOrNull("analysisStatus"), AnalysisStatus.PENDING)
            // Незавершённый анализ переводим в PENDING, чтобы не оставлять «зависший» статус
            val status = if (rawStatus == AnalysisStatus.IN_PROGRESS) AnalysisStatus.PENDING else rawStatus

            val moves = g.rows("analyzedMoves").map { m ->
                val san = m.nonBlankString("san")
                AnalyzedMoveEntry(
                    moveIndex = m.requireInt("moveIndex").coerceAtLeast(0),
                    moveNumber = (m.intOrNull("moveNumber") ?: 1).coerceAtLeast(1),
                    color = enumOrDefault(m.stringOrNull("color"), ChessColor.WHITE),
                    quality = enumOrDefault(m.stringOrNull("quality"), MoveQuality.GOOD),
                    san = san,
                    bestMove = m.stringOrNull("bestMove")?.takeIf { it.isNotBlank() },
                    evalBefore = m.intOrNull("evalBefore") ?: 0,
                    evalAfter = m.intOrNull("evalAfter") ?: 0,
                    evalLoss = (m.intOrNull("evalLoss") ?: 0).coerceAtLeast(0),
                    comment = m.stringOrNull("comment")
                )
            }

            val mistakes = g.rows("mistakes").map { mk ->
                val best = mk.nonBlankString("bestMove")
                val userMv = mk.nonBlankString("userMove")
                MistakeEntry(
                    themeId = mk.longOrNull("themeId") ?: 10L,
                    moveNumber = (mk.intOrNull("moveNumber") ?: 1).coerceAtLeast(1),
                    color = enumOrDefault(mk.stringOrNull("color"), color),
                    evaluationLoss = (mk.intOrNull("evaluationLoss") ?: 0).coerceAtLeast(0),
                    mistakeType = enumOrDefault(mk.stringOrNull("mistakeType"), MistakeType.MISTAKE),
                    bestMove = best,
                    userMove = userMv,
                    comment = mk.stringOrNull("comment"),
                    fenBefore = mk.stringOrNull("fenBefore")
                )
            }

            GameEntry(
                pgnData = pgn,
                playerColor = color,
                timeControl = g.stringOrNull("timeControl"),
                playedAt = (g.longOrNull("playedAt") ?: exportedAtMs).coerceAtLeast(0L),
                analysisStatus = status,
                analyzedAt = g.longOrNull("analyzedAt"),
                accuracy = g.doubleOrNull("accuracy")?.coerceIn(0.0, 100.0),
                averageEvaluationLoss = g.intOrNull("averageEvaluationLoss")?.coerceAtLeast(0),
                totalMistakes = (g.intOrNull("totalMistakes") ?: 0).coerceAtLeast(0),
                blundersCount = (g.intOrNull("blundersCount") ?: 0).coerceAtLeast(0),
                mistakesCount = (g.intOrNull("mistakesCount") ?: 0).coerceAtLeast(0),
                inaccuraciesCount = (g.intOrNull("inaccuraciesCount") ?: 0).coerceAtLeast(0),
                evaluationsJson = g.stringOrNull("evaluationsJson"),
                analyzedMoves = moves,
                mistakes = mistakes
            )
        }

        val exercises = payload.rows("personalExercises").map { ex ->
            val fen = ex.nonBlankString("fenPosition").trim()
            val solution = ex.strings("solutionMoves").map { it.trim() }
            require(solution.isNotEmpty() && solution.none { it.isEmpty() }) { "Пустое решение задачи" }
            val pattern = enumOrDefault(ex.stringOrNull("tacticalPattern"), TacticalPattern.MIXED)
            val gameIdx = ex.optionalIndex("sourceGameIndex", games.indices)

            PersonalExerciseEntry(
                fenPosition = fen,
                solutionMoves = solution,
                tacticalPattern = pattern,
                patternClusterId = ex.stringOrNull("patternClusterId")?.ifBlank { null }
                    ?: "${pattern.name}_RESTORED",
                difficultyRating = (ex.intOrNull("difficultyRating") ?: 50).coerceIn(0, 100),
                patternFeatures = ex.strings("patternFeatures"),
                theme = enumOrDefault(ex.stringOrNull("theme"), TacticalTheme.TACTIC),
                rating = (ex.intOrNull("rating") ?: 1500).coerceIn(100, 3500),
                createdFrom = enumOrDefault(ex.stringOrNull("createdFrom"), ExerciseSource.MISTAKE),
                sourceGameIndex = gameIdx,
                sourceMoveNumber = ex.intOrNull("sourceMoveNumber")?.takeIf { it > 0 },
                createdAt = (ex.longOrNull("createdAt") ?: exportedAtMs).coerceAtLeast(0L),
                timesShown = (ex.intOrNull("timesShown") ?: 0).coerceAtLeast(0),
                timesSolved = (ex.intOrNull("timesSolved") ?: 0).coerceAtLeast(0),
                successRate = (ex.intOrNull("successRate") ?: 0).coerceIn(0, 100)
            )
        }

        val attempts = payload.rows("attempts").map { a ->
            val solved = requireNotNull(a.boolOrNull("solved")) { "Отсутствует результат попытки" }
            val pIdx = a.optionalIndex("personalExerciseIndex", exercises.indices)
            val curated = a.stringOrNull("curatedId")?.trim()?.takeIf { it.isNotEmpty() }
            val fen = a.stringOrNull("exerciseFen")?.trim()?.takeIf { it.isNotEmpty() }
            require(pIdx != null || curated != null || fen != null) { "У попытки нет ссылки на задачу" }

            AttemptEntry(
                personalExerciseIndex = pIdx,
                curatedId = curated,
                exerciseFen = fen,
                solved = solved,
                timeSpentSeconds = (a.intOrNull("timeSpentSeconds") ?: 0).coerceAtLeast(0),
                movesMade = a.strings("movesMade"),
                attemptedAt = (a.longOrNull("attemptedAt") ?: exportedAtMs).coerceAtLeast(0L)
            )
        }

        return BackupSnapshot(
            schemaVersion = version,
            exportedAtMs = exportedAtMs,
            profile = profile,
            games = games,
            personalExercises = exercises,
            attempts = attempts
        )
    }

    private inline fun <reified E : Enum<E>> enumOrDefault(raw: String?, fallback: E): E {
        if (raw.isNullOrBlank()) return fallback
        return enumValues<E>().firstOrNull { it.name == raw.trim() } ?: fallback
    }

    fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(((b.toInt() ushr 4) and 0xF).toString(16))
            sb.append((b.toInt() and 0xF).toString(16))
        }
        return sb.toString()
    }
}
