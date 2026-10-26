# Shrink, optimize and obfuscate. Only the two entry points Android starts by name are kept.
-keep public class com.walkietalkie.wifi.MainActivity { public <init>(); }
-keep public class com.walkietalkie.wifi.RadioService { public <init>(); }

-dontpreverify
-dontnote
-dontwarn
-optimizationpasses 5
-allowaccessmodification
-repackageclasses ''
-mergeinterfacesaggressively
# Optimizations known to be safe for the Android runtime
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
# No stack-trace attributes in the release build
-renamesourcefileattribute ''
