package github.lms.lemuel.reconciliation.domain

/**
 * Typed domain exception for reconciliation invariant violations (negative
 * tolerance, missing EXPECTED/ACTUAL source etc.) — the domain does not throw
 * bare stdlib exceptions.
 *
 * Extends [IllegalArgumentException] so the web boundary's 400 mapping and
 * Kotlin's argument-error semantics are preserved, while call sites and logs
 * can distinguish a domain validation failure from an arbitrary IAE.
 * (Same convention as the Java services' `*InvariantViolationException`.)
 */
class ReconciliationInvariantViolationException(message: String) : IllegalArgumentException(message)
