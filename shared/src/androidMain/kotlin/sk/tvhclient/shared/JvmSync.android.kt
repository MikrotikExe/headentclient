package sk.tvhclient.shared

/** M750-fix: on Android the common [JvmSync] is the JVM's own @Synchronized. */
actual typealias JvmSync = kotlin.jvm.Synchronized
