# Keep JNI entry points in StockfishEngine
-keepclasseswithmembernames class com.example.chessmentor.data.engine.StockfishEngine {
    native <methods>;
}

# Keep Room entities & enums serialized by name
-keep class com.example.chessmentor.domain.entity.** { *; }

# Keep chesslib reflective / enum lookups safe under R8
-keep class com.github.bhlangonijr.chesslib.** { *; }

# Preserve source file and line numbers in stack traces
-keepattributes SourceFile,LineNumberTable
