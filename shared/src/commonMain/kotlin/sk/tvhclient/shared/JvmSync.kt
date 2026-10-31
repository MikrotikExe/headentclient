package sk.tvhclient.shared

/**
 * M750-fix: `kotlin.jvm.Synchronized` is deprecated in common code (Kotlin/Native has no
 * monitor on a class instance, so the iOS compile check refused it). This is our own optional
 * expectation: on Android it is the JVM annotation itself (the method takes the instance's
 * monitor exactly as before); on iOS there is no actual and the annotation is simply absent —
 * the app is Android-only, iOS is only compile-checked.
 */
@OptIn(ExperimentalMultiplatform::class)
@OptionalExpectation
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
@Retention(AnnotationRetention.SOURCE)
@MustBeDocumented
expect annotation class JvmSync()
