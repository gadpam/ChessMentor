package com.example.chessmentor.data.local

import androidx.room.withTransaction
import com.example.chessmentor.BuildConfig
import com.example.chessmentor.domain.entity.User
import java.util.UUID

/** Local debug persona. Excluded from release source set; this marker is not a password hash. */
internal class TesterProfiles(private val db: AppDatabase) {
    suspend fun open(onlyIfEmpty: Boolean): User? {
        if (!BuildConfig.DEBUG) return null
        return db.withTransaction {
            val users = db.userDao()
            if (onlyIfEmpty && users.hasUsers()) return@withTransaction null
            val marker = "local-debug-persona:v1"
            users.findPasswordlessProfile(marker) ?: run {
                val token = UUID.randomUUID().toString()
                val tester = User(
                    nickname = "Тестировщик-${token.take(6)}",
                    email = "$token@example.invalid",
                    passwordHash = marker
                )
                tester.copy(id = users.insert(tester))
            }
        }
    }
}
