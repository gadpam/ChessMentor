# Сторонние компоненты

## Stockfish (GPLv3)

Нативная библиотека `stockfish-lib` собирается из исходников Stockfish `sf_19`
(`app/src/main/cpp/stockfish/`). Исходники распространяются по лицензии GNU GPL v3,
полный текст лицензии: `app/src/main/cpp/stockfish/COPYING.txt`.

Изменения относительно оригинала:
- удалены `main.cpp`, `universal/`, `Makefile` (не нужны для сборки под Android);
- `NNUE_EMBEDDING_OFF`: нейросеть не встраивается в библиотеку, а загружается
  при первом запуске из официальных источников (`data/engine/NnueDownloader.kt`)
  с проверкой SHA-256 (первые 12 символов в имени файла, как в `scripts/net.sh`).

JNI-обёртка (`app/src/main/cpp/stockfish_jni.cpp`, `stockfish_bridge.*`) написана для
этого проекта.

Исходный код Stockfish: https://github.com/official-stockfish/Stockfish

## Лицензия проекта

Проект ChessMentor распространяется под GNU GPL v3 (файл `LICENSE`), так как включает Stockfish (GPLv3).
