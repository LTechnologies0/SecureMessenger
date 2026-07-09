# Privacy-safe release logging — strip verbose/debug log calls from release builds.
# Tor-only messenger: never leak JIDs, room IDs, tokens, or stack traces to system logs.

-assumenosideeffects class timber.log.Timber {
    public static void v(...);
    public static void d(...);
    public static void i(...);
}
