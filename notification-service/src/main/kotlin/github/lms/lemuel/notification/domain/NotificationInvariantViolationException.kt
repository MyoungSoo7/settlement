package github.lms.lemuel.notification.domain

/**
 * Typed domain exception for notification invariant violations (blank
 * recipient/subject etc.) — the domain does not throw bare stdlib exceptions.
 *
 * Extends [IllegalArgumentException] so the web boundary's 400 mapping and
 * Kotlin's argument-error semantics are preserved, while call sites and logs
 * can distinguish a domain validation failure from an arbitrary IAE.
 * (Same convention as the Java services' `*InvariantViolationException`.)
 */
class NotificationInvariantViolationException(message: String) : IllegalArgumentException(message)
