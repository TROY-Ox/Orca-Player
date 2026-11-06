# Extended build only: CloudStream extensions call the CloudStream library API and the libraries
# it exposes by name at runtime, so none of them may be shrunk or renamed.
-keep class com.lagradost.** { *; }
-keep class com.troy.orcaplayer.sources.cloudstream.** { *; }
-keep class okhttp3.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn com.lagradost.**
