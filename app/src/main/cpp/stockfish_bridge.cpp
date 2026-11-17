// app/src/main/cpp/stockfish_bridge.cpp

#include "stockfish_bridge.h"

#include <algorithm>
#include <cstdlib>
#include <fstream>
#include <sstream>
#include <utility>

#include "attacks.h"
#include "engine.h"
#include "misc.h"
#include "position.h"
#include "search.h"
#include "uci.h"

namespace chessmentor {

int StockfishBridge::parseScore(const std::string& text, bool* ok) {
    std::istringstream is(text);
    std::string        kind;
    long               value = 0;
    is >> kind >> value;
    if (!is) {
        *ok = false;
        return 0;
    }
    *ok = true;

    if (kind == "cp") {
        // Ограничиваем cp ниже порога мата, чтобы обычная оценка не перепуталась с матом.
        const long maxCp = kMateThreshold - 100;
        const long clamped = std::clamp(value, -maxCp, maxCp);
        return static_cast<int>(clamped);
    }

    if (kind == "mate") {
        // mate 0 означает, что сторона, которая ходит, уже в мате.
        if (value == 0)
            return -kMateValue;

        // Ограничиваем множитель, чтобы мат в >100 ходов не упал ниже kMateThreshold.
        const long maxEncodedMoves = (kMateValue - kMateThreshold) / 100;
        const long movesToMate     = std::min(std::labs(value), maxEncodedMoves);
        const int  magnitude       = kMateValue - static_cast<int>(movesToMate * 100);
        return value > 0 ? magnitude : -magnitude;
    }

    *ok = false;
    return 0;
}

void StockfishBridge::initGlobalTables() {
    static std::once_flag onceFlag;
    std::call_once(onceFlag, []() {
        Stockfish::Attacks::init();
        Stockfish::Position::init();
    });
}

StockfishBridge::StockfishBridge() = default;

StockfishBridge::~StockfishBridge() { destroy(); }

bool StockfishBridge::init(const std::string& netPath) {
    std::lock_guard<std::mutex> lock(opMutex_);

    if (engine_ && netPath_ == netPath)
        return true;

    destroyLocked();

    // Проверка файла до передачи в Stockfish: при ошибке загрузки сети движок завершит процесс.
    {
        std::ifstream file(netPath, std::ios::binary | std::ios::ate);
        if (!file)
            return false;
        const auto size = file.tellg();
        if (size < 0 || static_cast<long>(size) < kMinNetFileBytes)
            return false;
    }

    // Обязательная инициализация таблиц атак и Zobrist-хешей (как в Stockfish main.cpp).
    initGlobalTables();

    engine_ = std::make_unique<Stockfish::Engine>(std::nullopt);

    // Коллбэки вызываются из потоков поиска, но читаем мы их только после wait_for_search_finished().
    engine_->set_on_update_full([this](const Stockfish::Engine::InfoFull& info) {
        if (info.multiPV != 1)
            return;
        bool ok    = false;
        int  score = parseScore(Stockfish::UCIEngine::format_score(info.score), &ok);
        if (!ok)
            return;
        last_.hasScore = true;
        last_.score    = score;
        last_.pv       = std::string(info.pv);
    });

    engine_->set_on_update_no_moves([this](const Stockfish::Engine::InfoShort& info) {
        bool ok    = false;
        int  score = parseScore(Stockfish::UCIEngine::format_score(info.score), &ok);
        if (!ok)
            return;
        last_.hasScore = true;
        last_.score    = score;
    });

    engine_->set_on_bestmove([this](std::string_view bestmove, std::string_view) {
        last_.bestMove = std::string(bestmove);
    });

    // Установка EvalFile через UCI-парсер запускает загрузку сети.
    std::istringstream is("name EvalFile value " + netPath);
    engine_->get_options().setoption(is);

    {
        std::lock_guard<std::mutex> stopLock(stopMutex_);
        activeEngine_ = engine_.get();
    }

    netPath_ = netPath;
    return true;
}

bool StockfishBridge::isReady() const {
    std::lock_guard<std::mutex> lock(opMutex_);
    return engine_ != nullptr;
}

void StockfishBridge::setOption(const std::string& name, const std::string& value) {
    std::lock_guard<std::mutex> lock(opMutex_);
    if (!engine_)
        return;
    engine_->wait_for_search_finished();
    std::istringstream is("name " + name + " value " + value);
    engine_->get_options().setoption(is);
}

bool StockfishBridge::search(const std::string& fen, int depth, SearchResult& out) {
    std::lock_guard<std::mutex> lock(opMutex_);
    if (!engine_)
        return false;

    engine_->wait_for_search_finished();
    stopRequested_.store(false, std::memory_order_release);
    last_ = SearchResult{};

    if (engine_->set_position(fen, {}).has_value())
        return false;

    Stockfish::Search::LimitsType limits;
    limits.startTime = Stockfish::now();
    limits.depth     = std::clamp(depth, 1, 64);

    engine_->go(limits);

    // Если stop() успели вызвать прямо перед стартом поиска — останавливаем немедленно.
    if (stopRequested_.load(std::memory_order_acquire)) {
        engine_->stop();
    }

    engine_->wait_for_search_finished();

    out = last_;
    return true;
}

int StockfishBridge::evaluate(const std::string& fen, int depth, bool* ok) {
    SearchResult result;
    const bool   searched = search(fen, depth, result);
    *ok = searched && result.hasScore;
    return *ok ? result.score : 0;
}

std::string StockfishBridge::bestMove(const std::string& fen, int depth) {
    SearchResult result;
    if (!search(fen, depth, result))
        return {};
    // "(none)" означает, что ходов нет (мат или пат).
    if (result.bestMove == "(none)")
        return {};
    return result.bestMove;
}

StockfishBridge::AnalysisLine StockfishBridge::bestLine(const std::string& fen, int depth) {
    AnalysisLine line;
    SearchResult result;
    if (!search(fen, depth, result))
        return line;
    if (!result.hasScore)
        return line;

    // В терминальных позициях (мат/пат) bestMove == "(none)", но оценка валидна.
    const bool hasLegalMove = !result.bestMove.empty() && result.bestMove != "(none)";
    line.ok                 = true;
    line.bestMove           = hasLegalMove ? result.bestMove : std::string{};
    line.score              = result.score;
    line.pv                 = hasLegalMove ? (result.pv.empty() ? result.bestMove : result.pv)
                                           : std::string{};
    return line;
}

void StockfishBridge::stop() {
    stopRequested_.store(true, std::memory_order_release);
    std::lock_guard<std::mutex> stopLock(stopMutex_);
    if (activeEngine_ != nullptr) {
        activeEngine_->stop();
    }
}

void StockfishBridge::destroy() {
    // Сначала прерываем активный поиск, чтобы не ждать его завершения до полной глубины.
    stop();
    std::lock_guard<std::mutex> lock(opMutex_);
    destroyLocked();
}

void StockfishBridge::destroyLocked() {
    {
        std::lock_guard<std::mutex> stopLock(stopMutex_);
        if (activeEngine_ != nullptr) {
            activeEngine_->stop();
            activeEngine_ = nullptr;
        }
    }
    engine_.reset();  // ~Engine дожидается окончания поиска
    netPath_.clear();
    last_ = SearchResult{};
    stopRequested_.store(false, std::memory_order_release);
}

}  // namespace chessmentor
