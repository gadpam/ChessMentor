package com.example.chessmentor.data.local

import com.example.chessmentor.domain.entity.User

/** Release stub: local debug tester persona and sample fixtures are excluded from release builds. */
internal class TesterProfiles(@Suppress("UNUSED_PARAMETER") db: AppDatabase) {
    @Suppress("UNUSED_PARAMETER", "RedundantSuspendModifier")
    suspend fun open(onlyIfEmpty: Boolean): User? = null

    @Suppress("UNUSED_PARAMETER", "RedundantSuspendModifier")
    suspend fun seedSampleLibraryIfEmpty(userId: Long): Int = 0
}
