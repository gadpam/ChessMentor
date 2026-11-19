package com.example.chessmentor.data.local

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.chessmentor.data.local.TypeConverters as MyConverters
import com.example.chessmentor.data.local.dao.AnalyzedMoveDao
import com.example.chessmentor.data.local.dao.ExerciseDao
import com.example.chessmentor.data.local.dao.GameDao
import com.example.chessmentor.data.local.dao.MistakeDao
import com.example.chessmentor.data.local.dao.UserDao
import com.example.chessmentor.domain.entity.AnalyzedMove
import com.example.chessmentor.domain.entity.Exercise
import com.example.chessmentor.domain.entity.ExerciseAttempt
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.entity.Mistake
import com.example.chessmentor.domain.entity.User

@Database(
    entities = [
        User::class,
        Game::class,
        Mistake::class,
        Exercise::class,
        ExerciseAttempt::class,
        AnalyzedMove::class
    ],
    version = DatabaseMigrations.CURRENT_VERSION,
    exportSchema = true
)
@TypeConverters(MyConverters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun userDao(): UserDao
    abstract fun gameDao(): GameDao
    abstract fun mistakeDao(): MistakeDao
    abstract fun exerciseDao(): ExerciseDao
    abstract fun analyzedMoveDao(): AnalyzedMoveDao

    companion object {
        private const val TAG = "AppDatabase"
        private const val DATABASE_NAME = "chess_mentor_db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Получить экземпляр базы данных (Singleton).
         *
         * При обновлении схемы применяются только явные недеструктивные миграции
         * ([DatabaseMigrations.ALL_MIGRATIONS]) в режиме журналирования WAL;
         * деструктивный откат разрешён исключительно при понижении версии APK (downgrade),
         * чтобы обновление приложения никогда не уничтожало партии и прогресс пользователя.
         */
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(appContext: Context): AppDatabase {
            Log.d(TAG, "Creating database instance (target version ${DatabaseMigrations.CURRENT_VERSION})")
            return Room.databaseBuilder(
                appContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(*DatabaseMigrations.ALL_MIGRATIONS)
                .fallbackToDestructiveMigrationOnDowngrade()
                .addCallback(DatabaseCallback())
                .build()
        }

        /** Очистить singleton-ссылку (для тестирования). */
        fun clearInstance() {
            INSTANCE = null
        }

        private class DatabaseCallback : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                Log.i(TAG, "Database created, schema version: ${db.version}")
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                // Гарантируем каскадное удаление зависимых строк при каждом открытии соединения
                db.execSQL("PRAGMA foreign_keys = ON")
                Log.d(TAG, "Database opened, schema version: ${db.version}")
            }

            override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                super.onDestructiveMigration(db)
                Log.w(TAG, "Destructive downgrade migration performed to schema version ${db.version}")
            }
        }
    }
}
