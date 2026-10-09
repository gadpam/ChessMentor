// app/src/main/cpp/stockfish_bridge.h
//
// Тонкая обёртка над Stockfish::Engine без зависимости от JNI.
// Вся логика работы с движком живёт здесь, JNI-файл только переводит типы.
//
// Соглашения:
//  * Все оценки (cp и mate) возвращаются с точки зрения стороны, которая ходит
//    в переданной позиции (как в UCI). Вызывающая сторона сама переводит их
//    в белую перспективу, если нужно.
//  * Мат кодируется так же, как в ChessEngine.kt: MATE_VALUE = 100000,
//    мат через N ходов = ±(100000 - N * 100).
//  * Экземпляр не потокобезопасен. Вызовы должны быть сериализованы снаружи
//    (в Kotlin это делает Mutex в StockfishEngine).

#ifndef CHESSMENTOR_STOCKFISH_BRIDGE_H
#define CHESSMENTOR_STOCKFISH_BRIDGE_H

#include <memory>
#include <string>

namespace Stockfish {
class Engine;
}

namespace chessmentor {

class StockfishBridge {
   public:
    static constexpr int kMateValue     = 100000;
    static constexpr int kMateThreshold = 90000;

    // Минимальный размер файла сети. Всё меньше считаем битым (например, обрыв загрузки).
    static constexpr long kMinNetFileBytes = 1L << 20;  // 1 MiB

    struct AnalysisLine {
        bool        ok = false;
        std::string bestMove;  // UCI, например "e2e4"
        int         score = 0; // см. соглашения выше
        std::string pv;        // ходы UCI через пробел
    };

    StockfishBridge();
    ~StockfishBridge();

    StockfishBridge(const StockfishBridge&)            = delete;
    StockfishBridge& operator=(const StockfishBridge&) = delete;

    // Создаёт движок и загружает сеть NNUE из файла netPath.
    // Возвращает false, если файл отсутствует или выглядит битым.
    // ВАЖНО: если сеть не загрузилась, Stockfish сам вызывает exit() при первом поиске,
    // поэтому проверка здесь обязательна. Хеш файла проверяется до вызова (см. NnueDownloader.kt).
    bool init(const std::string& netPath);

    bool isReady() const;

    // Передача UCI-опции, например "Threads" или "Hash".
    void setOption(const std::string& name, const std::string& value);

    // Оценка позиции в cp (или мат-кодировка) с точки зрения стороны, которая ходит.
    // ok=false при ошибке (неверный FEN, движок не готов).
    int evaluate(const std::string& fen, int depth, bool* ok);

    // Лучший ход в UCI или пустая строка, если ходов нет.
    std::string bestMove(const std::string& fen, int depth);

    AnalysisLine bestLine(const std::string& fen, int depth);

    void destroy();

   private:
    struct SearchResult {
        bool        hasScore = false;
        int         score    = 0;
        std::string pv;
        std::string bestMove;
    };

    // Запускает поиск на заданной глубине и возвращает собранный результат.
    bool search(const std::string& fen, int depth, SearchResult& out);

    std::unique_ptr<Stockfish::Engine> engine_;
    std::string                        netPath_;
    SearchResult                       last_;
};

}  // namespace chessmentor

#endif  // CHESSMENTOR_STOCKFISH_BRIDGE_H
