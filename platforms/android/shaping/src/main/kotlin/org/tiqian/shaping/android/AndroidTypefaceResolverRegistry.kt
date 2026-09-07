package org.tiqian.shaping.android

/**
 * `HostTypefaceResolver`: the process-wide [AndroidTypefaceResolver] the platform text path
 * measures and draws with. Defaults to [SystemAndroidTypefaceResolver]; a host that ships its own
 * font files installs a resolver before the first paragraph is measured, and every measurer built
 * afterwards plus the renderer read the same instance.
 */
object AndroidTypefaceResolverRegistry {
    @Volatile
    private var installed: AndroidTypefaceResolver = SystemAndroidTypefaceResolver()

    val current: AndroidTypefaceResolver
        get() = installed

    fun install(resolver: AndroidTypefaceResolver) {
        installed = resolver
    }

    fun reset() {
        installed = SystemAndroidTypefaceResolver()
    }
}
