# Extensions call the CloudStream library API and its helper libraries by name at runtime,
# so they are kept unshrunk and unrenamed in the extended build.
-keep class com.lagradost.** { *; }
-keep class com.troy.orcaplayer.sources.cloudstream.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class org.jsoup.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class org.mozilla.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class kotlin.** { *; }
-dontwarn com.lagradost.**
-dontwarn org.mozilla.**
-dontwarn com.fasterxml.jackson.**
-dontwarn org.jsoup.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn org.schabi.newpipe.**
