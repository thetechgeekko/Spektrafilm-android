# Stage 1: shrink only, no renaming (near-zero JNI risk). Obfuscation deferred to Stage 2.
-dontobfuscate

# ---- Native (name-based JNI) boundary: all four *_jni.cpp bind via exported
# Java_<fqcn>_<method> symbols (no RegisterNatives) and resolve classes/methods/ctors
# by literal string from C++. Keep these un-renamed and un-removed. ----
-keep class com.spectrafilm.engine.** { *; }
-keep class com.spectrafilm.libraw.RawDecoder { *; }
-keep class com.spectrafilm.libraw.RawDecoder$NativeResult { *; }
-keep class com.spectrafilm.libraw.RawDecodeException { *; }
-keep class com.spectrafilm.tiffwriter.TiffWriter { *; }
-keep class com.spectrafilm.pngwriter.PngWriter { *; }
-keepclasseswithmembernames class * { native <methods>; }

# ---- Enum name<->value persistence (prefs/presets/Serializable saver) ----
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}
