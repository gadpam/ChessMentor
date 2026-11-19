# ============================================================
# ChessMentor R8 / ProGuard configuration
# ============================================================

# 1. JNI entry points bound in app/src/main/cpp/stockfish_jni.cpp
-keep class com.example.chessmentor.data.engine.StockfishEngine {
    native <methods>;
}
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# 2. Room database, generated *_Impl classes, DAOs, TypeConverters & Entities/Enums
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class * extends androidx.room.RoomDatabase$Callback { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep class com.example.chessmentor.data.local.**_Impl { *; }
-keep class com.example.chessmentor.data.local.dao.**_Impl { *; }
-keep class com.example.chessmentor.data.local.TypeConverters { *; }
-keep class com.example.chessmentor.domain.entity.** { *; }

# Keep enum values()/valueOf() for persisted settings, themes & backup serialization
-keepclassmembers enum com.example.chessmentor.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 3. chesslib (uses reflective/enum lookups across Square, Piece, File, Rank, Side)
-keep class com.github.bhlangonijr.chesslib.** { *; }
-dontwarn com.github.bhlangonijr.chesslib.**

# 4. Readable stack traces & generic type metadata for Room/Kotlin
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
