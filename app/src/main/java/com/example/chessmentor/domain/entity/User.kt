package com.example.chessmentor.domain.entity

import java.time.Instant

/**
 * Сущность: Пользователь системы
 *
 * Представляет шахматиста с его учётными данными,
 * рейтингом и уровнем мастерства
 */
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey


@Entity(tableName = "users")
data class User(
    @PrimaryKey(autoGenerate = true)
    val id: Long? = null,

    val nickname: String,
    val email: String,
    val passwordHash: String,

    val rating: Int = 1200,
    val skillLevel: SkillLevel = SkillLevel.fromRating(rating),

    val createdAt: Long = System.currentTimeMillis(), // Используем Long для Room
    val lastLogin: Long? = null,
    val isPremium: Boolean = false,

    val isSoundEnabled: Boolean = true,
    val preferredTheme: String = "Classic"
) {

    init {
        // Валидация при создании объекта
        require(nickname.length in 3..50) {
            "Никнейм должен быть от 3 до 50 символов"
        }
        require(rating in 0..3500) {
            "Рейтинг должен быть от 0 до 3500"
        }
        require(email.contains("@")) {
            "Некорректный email"
        }
    }

    /**
     * Копирование с обновлённым рейтингом
     * Автоматически пересчитывает skillLevel
     */
    fun withNewRating(newRating: Int): User {
        return copy(
            rating = newRating,
            skillLevel = SkillLevel.fromRating(newRating)
        )
    }

    /**
     * Копирование с обновлённым временем последнего входа
     */
    fun withLogin(): User {
        return copy(lastLogin = System.currentTimeMillis())
    }

    /**
     * Копирование с новым хешем пароля (например, при миграции на PBKDF2)
     */
    fun withPasswordHash(newHash: String): User {
        return copy(passwordHash = newHash)
    }

    /**
     * Активировать Premium
     */
    fun toPremium(): User {
        return copy(isPremium = true)
    }

    /**
     * Строковое представление (без пароля!)
     */
    override fun toString(): String {
        return "User(id=$id, nickname='$nickname', rating=$rating, skillLevel=$skillLevel)"
    }

    fun withSettings(sound: Boolean, theme: String): User {
        return copy(isSoundEnabled = sound, preferredTheme = theme)
    }
}