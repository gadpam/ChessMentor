// data/local/DatabaseMigrations.kt
package com.example.chessmentor.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Явные недеструктивные миграции Room для всех версий схемы `1 → 5`.
 *
 * Каждая миграция идемпотентна: перед `ALTER TABLE ... ADD COLUMN` проверяет
 * фактический состав колонок через `PRAGMA table_info(...)`, а таблицы и индексы
 * создаёт через `IF NOT EXISTS`, сохраняя пользовательские партии, ошибки и прогресс.
 */
object DatabaseMigrations {

    private const val TAG = "DatabaseMigrations"
    const val CURRENT_VERSION = 5

    /** Миграция `1 → 2`: гарантирует базовые таблицы пользователей, партий, ошибок и задач. */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Running safe migration 1 -> 2")
            ensureCoreTables(db)
        }
    }

    /** Миграция `2 → 3`: добавляет таблицу пошагового анализа `analyzed_moves` и её индекс. */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Running safe migration 2 -> 3")
            ensureAnalyzedMovesTable(db)
        }
    }

    /** Миграция `3 → 4`: добавляет хранение линии оценок `evaluationsJson` в `games`. */
    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Running safe migration 3 -> 4")
            addColumnIfMissing(db, "games", "evaluationsJson", "TEXT DEFAULT NULL")
        }
    }

    /**
     * Миграция `4 → 5`: доводит все таблицы и индексы до актуальной схемы v5
     * (настройки звука/темы в `users`, `fenBefore` в `mistakes`, поля тактических
     * паттернов/кластеров/статистики в `exercises` и все индексы) без потери данных.
     */
    val MIGRATION_4_5: Migration = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Running safe migration 4 -> 5")
            ensureCoreTables(db)
            ensureAnalyzedMovesTable(db)

            // users
            addColumnIfMissing(db, "users", "lastLogin", "INTEGER DEFAULT NULL")
            addColumnIfMissing(db, "users", "isPremium", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "users", "isSoundEnabled", "INTEGER NOT NULL DEFAULT 1")
            addColumnIfMissing(db, "users", "preferredTheme", "TEXT NOT NULL DEFAULT 'Классическая'")

            // games
            addColumnIfMissing(db, "games", "timeControl", "TEXT DEFAULT NULL")
            addColumnIfMissing(db, "games", "analyzedAt", "INTEGER DEFAULT NULL")
            addColumnIfMissing(db, "games", "accuracy", "REAL DEFAULT NULL")
            addColumnIfMissing(db, "games", "averageEvaluationLoss", "INTEGER DEFAULT NULL")
            addColumnIfMissing(db, "games", "totalMistakes", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "games", "blundersCount", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "games", "mistakesCount", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "games", "inaccuraciesCount", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "games", "evaluationsJson", "TEXT DEFAULT NULL")

            // mistakes
            addColumnIfMissing(db, "mistakes", "comment", "TEXT DEFAULT NULL")
            addColumnIfMissing(db, "mistakes", "fenBefore", "TEXT DEFAULT NULL")

            // exercises
            val addedDifficulty = addColumnIfMissing(
                db, "exercises", "difficultyRating", "INTEGER NOT NULL DEFAULT 50"
            )
            addColumnIfMissing(db, "exercises", "tacticalPattern", "TEXT NOT NULL DEFAULT 'MIXED'")
            addColumnIfMissing(db, "exercises", "patternClusterId", "TEXT NOT NULL DEFAULT 'MIXED_MEDIUM'")
            addColumnIfMissing(db, "exercises", "patternFeatures", "TEXT NOT NULL DEFAULT '[]'")
            addColumnIfMissing(db, "exercises", "theme", "TEXT NOT NULL DEFAULT 'TACTIC'")
            addColumnIfMissing(db, "exercises", "rating", "INTEGER NOT NULL DEFAULT 1500")
            addColumnIfMissing(db, "exercises", "createdFrom", "TEXT NOT NULL DEFAULT 'MISTAKE'")
            addColumnIfMissing(db, "exercises", "sourceGameId", "INTEGER DEFAULT NULL")
            addColumnIfMissing(db, "exercises", "sourceMoveNumber", "INTEGER DEFAULT NULL")
            addColumnIfMissing(db, "exercises", "createdAt", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "exercises", "timesShown", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "exercises", "timesSolved", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "exercises", "successRate", "INTEGER NOT NULL DEFAULT 0")

            if (addedDifficulty) {
                // Пересчитываем difficultyRating (0..100) из старого поля rating Эло
                db.execSQL(
                    """
                    UPDATE exercises
                    SET difficultyRating = MAX(5, MIN(95, ((rating - 600) * 100) / 1800))
                    """.trimIndent()
                )
            }

            ensureAllIndices(db)
        }
    }

    val ALL_MIGRATIONS: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5
    )

    private fun ensureCoreTables(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `users` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT,
                `nickname` TEXT NOT NULL,
                `email` TEXT NOT NULL,
                `passwordHash` TEXT NOT NULL,
                `rating` INTEGER NOT NULL,
                `skillLevel` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `lastLogin` INTEGER,
                `isPremium` INTEGER NOT NULL,
                `isSoundEnabled` INTEGER NOT NULL,
                `preferredTheme` TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `games` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT,
                `userId` INTEGER NOT NULL,
                `pgnData` TEXT NOT NULL,
                `playerColor` TEXT NOT NULL,
                `timeControl` TEXT,
                `playedAt` INTEGER NOT NULL,
                `analysisStatus` TEXT NOT NULL,
                `analyzedAt` INTEGER,
                `accuracy` REAL,
                `averageEvaluationLoss` INTEGER,
                `totalMistakes` INTEGER NOT NULL,
                `blundersCount` INTEGER NOT NULL,
                `mistakesCount` INTEGER NOT NULL,
                `inaccuraciesCount` INTEGER NOT NULL,
                `evaluationsJson` TEXT,
                FOREIGN KEY(`userId`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `mistakes` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT,
                `gameId` INTEGER NOT NULL,
                `themeId` INTEGER NOT NULL,
                `moveNumber` INTEGER NOT NULL,
                `color` TEXT NOT NULL,
                `evaluationLoss` INTEGER NOT NULL,
                `mistakeType` TEXT NOT NULL,
                `bestMove` TEXT NOT NULL,
                `userMove` TEXT NOT NULL,
                `comment` TEXT,
                `fenBefore` TEXT,
                FOREIGN KEY(`gameId`) REFERENCES `games`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `exercises` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT,
                `fenPosition` TEXT NOT NULL,
                `solutionMoves` TEXT NOT NULL,
                `tacticalPattern` TEXT NOT NULL,
                `patternClusterId` TEXT NOT NULL,
                `difficultyRating` INTEGER NOT NULL,
                `patternFeatures` TEXT NOT NULL,
                `theme` TEXT NOT NULL,
                `rating` INTEGER NOT NULL,
                `createdFrom` TEXT NOT NULL,
                `sourceGameId` INTEGER,
                `sourceMoveNumber` INTEGER,
                `createdAt` INTEGER NOT NULL,
                `timesShown` INTEGER NOT NULL,
                `timesSolved` INTEGER NOT NULL,
                `successRate` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `exercise_attempts` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT,
                `userId` INTEGER NOT NULL,
                `exerciseId` INTEGER NOT NULL,
                `solved` INTEGER NOT NULL,
                `timeSpentSeconds` INTEGER NOT NULL,
                `movesMade` TEXT NOT NULL,
                `attemptedAt` INTEGER NOT NULL,
                FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        ensureAllIndices(db)
    }

    private fun ensureAnalyzedMovesTable(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `analyzed_moves` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT,
                `gameId` INTEGER NOT NULL,
                `moveIndex` INTEGER NOT NULL,
                `moveNumber` INTEGER NOT NULL,
                `color` TEXT NOT NULL,
                `quality` TEXT NOT NULL,
                `san` TEXT NOT NULL,
                `bestMove` TEXT,
                `evalBefore` INTEGER NOT NULL,
                `evalAfter` INTEGER NOT NULL,
                `evalLoss` INTEGER NOT NULL,
                `comment` TEXT,
                FOREIGN KEY(`gameId`) REFERENCES `games`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_analyzed_moves_gameId` ON `analyzed_moves` (`gameId`)"
        )
    }

    private fun ensureAllIndices(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_games_userId` ON `games` (`userId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_mistakes_gameId` ON `mistakes` (`gameId`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_exercises_tacticalPattern` ON `exercises` (`tacticalPattern`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_exercises_difficultyRating` ON `exercises` (`difficultyRating`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_exercises_patternClusterId` ON `exercises` (`patternClusterId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_exercise_attempts_exerciseId` ON `exercise_attempts` (`exerciseId`)"
        )
        if (hasTable(db, "analyzed_moves")) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_analyzed_moves_gameId` ON `analyzed_moves` (`gameId`)"
            )
        }
    }

    private fun hasTable(db: SupportSQLiteDatabase, tableName: String): Boolean {
        db.query(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
            arrayOf(tableName)
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    private fun hasColumn(db: SupportSQLiteDatabase, tableName: String, columnName: String): Boolean {
        if (!hasTable(db, tableName)) return false
        db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
            val nameIdx = cursor.getColumnIndex("name")
            if (nameIdx < 0) return false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIdx).equals(columnName, ignoreCase = true)) {
                    return true
                }
            }
        }
        return false
    }

    /** Добавляет колонку, только если её ещё нет. Возвращает `true`, если колонка была добавлена. */
    private fun addColumnIfMissing(
        db: SupportSQLiteDatabase,
        tableName: String,
        columnName: String,
        columnDefinition: String
    ): Boolean {
        if (!hasTable(db, tableName) || hasColumn(db, tableName, columnName)) {
            return false
        }
        Log.i(TAG, "Adding missing column $tableName.$columnName ($columnDefinition)")
        db.execSQL("ALTER TABLE `$tableName` ADD COLUMN `$columnName` $columnDefinition")
        return true
    }
}
