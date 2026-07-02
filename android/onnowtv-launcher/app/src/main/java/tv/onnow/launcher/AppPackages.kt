package tv.onnow.launcher

/**
 * Canonical Android APPLICATION IDs for the ON NOW TV suite.
 *
 * IMPORTANT: these are the runtime `applicationId`s that appear in
 * PackageManager — NOT the Kotlin source namespaces.  Vesper's Kotlin
 * namespace is `tv.vesper.app` but its installed package is
 * `tv.onnowtv.app`; confusing the two caused repeated
 * "app isn't installed" bugs.  Always reference these constants.
 */
object AppPackages {
    const val VESPER = "tv.onnowtv.app"
    const val KIDS = "tv.onnowtv.kids"
    const val TUNES = "tv.onnowtv.tunes"
}
