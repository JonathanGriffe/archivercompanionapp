# NanoHTTPD reflects on nothing, but keep its public surface for clarity.
-keep class fi.iki.elonen.** { *; }

# Bouncy Castle registers providers by name.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Room generated implementations.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
