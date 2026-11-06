# Extensions call the CloudStream library API and its helper libraries by name at runtime,
# so they are kept unshrunk and unrenamed in the extended build (Phase 8 adds the library).
-keep class com.lagradost.** { *; }
-keep class com.troy.orcaplayer.sources.cloudstream.** { *; }
