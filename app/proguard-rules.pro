# Ktor's IntellijIdeaDebugDetector reads java.lang.management (JVM-only, absent on Android). The call is
# wrapped in try/catch(Throwable) and falls back to "no debugger", so the missing classes are harmless.
# (These two lines are exactly what R8 reported as missing; broader -dontwarn rules are not needed.)
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# Ktor and kotlinx.coroutines ship their own consumer rules (volatile fields updated through
# AtomicFieldUpdater must keep their names). The server engine is referenced directly from
# PrintServer via embeddedServer(CIO, ...), nothing is service-loaded; keep CIO whole as a safety net.
-keep class io.ktor.server.cio.** { *; }
