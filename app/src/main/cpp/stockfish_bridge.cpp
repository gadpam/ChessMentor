// app/src/main/cpp/stockfish_bridge.cpp

#include "stockfish_bridge.h"

#include <cstdlib>
#include <fstream>
#include <sstream>
#include <utility>

#include "engine.h"
#include "misc.h"
#include "search.h"
#include "uci.h"

namespace chessmentor {

namespace {

// Переводит строку "cp 23" / "mate -3" (формат UCIEngine::format_score) в int
// в кодировке ChessEngine.kt.
int parseScore(const std::string& text, bool* ok) {
    std::istringstream is(text);
    std::string        kind;
    long               value = 0;
    is >> kind >> value;
    if (!is) {
        *ok = false;
        return 0;
    }
    *ok = true;

    if (kind == "cp")
        return static_cast<int>(value);

    if (kind == "mate") {
        // mate 0 означает, что сторона, которая ходит, уже в мате.
        if (value == 0)
            return -StockfishBridge::kMateValue;

        const long movesToMate = std::labs(value);
        const int  magnitude   = StockfishBridge::kMateValue - static_cast<int>(movesToMate * 100);
        return value > 0 ? magnitude : -magnitude;
    }

    *ok = false;
    return 0;
}

}  // namespace

StockfishBridge::StockfishBridge() = default;

StockfishBridge::~StockfishBridge() { destroy(); }

bool StockfishBridge::init(const std::string& netPath) {
    if (engine_ && netPath_ == netPath)
        return true;

    destroy();

    // Проверка файла до передачи в Stockfish: при ошибке загрузки сети движок завершит процесс.
    {
        std::ifstream file(netPath, std::ios::binary | std::ios::ate);
        if (!file)
            return false;
        if (static_cast<long>(file.tellg()) < kMinNetFileBytes)
            return false;
    }

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

    netPath_ = netPath;
    return true;
}

bool StockfishBridge::isReady() const { return engine_ != nullptr; }

void StockfishBridge::setOption(const std::string& name, const std::string& value) {
    if (!engine_)
        return;
    engine_->wait_for_search_finished();
    std::istringstream is("name " + name + " value " + value);
    engine_->get_options().setoption(is);
}

bool StockfishBridge::search(const std::string& fen, int depth, SearchResult& out) {
    if (!engine_)
        return false;

    engine_->wait_for_search_finished();
    last_ = SearchResult{};

    if (engine_->set_position(fen, {}).has_value())
        return false;

    Stockfish::Search::LimitsType limits;
    limits.startTime = Stockfish::now();
    limits.depth     = depth;

    engine_->go(limits);
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
    if (result.bestMove.empty() || result.bestMove == "(none)" || !result.hasScore)
        return line;

    line.ok       = true;
    line.bestMove = result.bestMove;
    line.score    = result.score;
    line.pv       = result.pv.empty() ? result.bestMove : result.pv;
    return line;
}

void StockfishBridge::destroy() {
    engine_.reset();  // ~Engine дожидается окончания поиска
    netPath_.clear();
    last_ = SearchResult{};
}

}  // namespace chessmentor
