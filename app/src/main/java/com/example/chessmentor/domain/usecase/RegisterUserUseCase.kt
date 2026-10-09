package com.example.chessmentor.domain.usecase

import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.domain.repository.UserRepository
import com.example.chessmentor.domain.security.PasswordHasher

/**
 * Use Case: Регистрация нового пользователя
 *
 * Бизнес-логика регистрации:
 * 1. Проверка уникальности email и nickname
 * 2. Валидация данных
 * 3. Хеширование пароля
 * 4. Сохранение пользователя
 */
class RegisterUserUseCase(
    private val userRepository: UserRepository
) {

    /**
     * Входные данные для регистрации
     */
    data class Input(
        val nickname: String,
        val email: String,
        val password: String,
        val rating: Int = 1200
    )

    /**
     * Результат регистрации
     */
    sealed class Result {
        data class Success(val user: User) : Result()
        data class Error(val message: String) : Result()
    }

    /**
     * Выполнить регистрацию
     */
    suspend fun execute(input: Input): Result {
        // Валидация nickname
        if (input.nickname.length < 3) {
            return Result.Error("Никнейм должен быть не менее 3 символов")
        }
        if (input.nickname.length > 50) {
            return Result.Error("Никнейм должен быть не более 50 символов")
        }

        // Валидация email
        if (!input.email.contains("@")) {
            return Result.Error("Некорректный email")
        }

        // Валидация пароля
        if (input.password.length < 6) {
            return Result.Error("Пароль должен быть не менее 6 символов")
        }

        // Валидация рейтинга
        if (input.rating !in 0..3500) {
            return Result.Error("Рейтинг должен быть от 0 до 3500")
        }

        // Проверка уникальности email
        if (userRepository.existsByEmail(input.email)) {
            return Result.Error("Пользователь с таким email уже существует")
        }

        // Проверка уникальности nickname
        if (userRepository.existsByNickname(input.nickname)) {
            return Result.Error("Пользователь с таким никнеймом уже существует")
        }

        // Хеширование пароля (PBKDF2-HMAC-SHA256)
        val passwordHash = PasswordHasher.hash(input.password)

        // Создание пользователя
        val newUser = User(
            nickname = input.nickname,
            email = input.email,
            passwordHash = passwordHash,
            rating = input.rating
        )

        // Сохранение
        val savedUser = userRepository.save(newUser)

        return Result.Success(savedUser)
    }
}
