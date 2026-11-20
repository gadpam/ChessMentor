package com.example.chessmentor.domain.entity

/**
 * Цвет фигур в шахматах
 */
enum class ChessColor {
    WHITE,
    BLACK;

    /**
     * Название на русском
     */
    fun getDisplayName(): String = when (this) {
        WHITE -> "Белые"
        BLACK -> "Чёрные"
    }

    /**
     * Противоположный цвет
     */
    fun opposite(): ChessColor = when (this) {
        WHITE -> BLACK
        BLACK -> WHITE
    }

}