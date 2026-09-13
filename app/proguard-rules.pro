
# Vosk talks to libvosk.so through JNA, and JNA finds native functions and maps
# Structure fields by reflecting on their names. R8 renames them, and a release
# build then fails inside Vosk's constructor, on the first transcription, on a
# phone, after the archive has already been recorded into. Neither AAR ships
# consumer rules, so these have to live here.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
# JNA references AWT for desktop callers. There is no AWT on Android and no
# code path that reaches it.
-dontwarn java.awt.**
