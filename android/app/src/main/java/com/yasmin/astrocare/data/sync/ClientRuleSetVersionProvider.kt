package com.yasmin.astrocare.data.sync

/**
 * Supplies the bundled rule-set version stamped into sync envelopes.
 *
 * An explicit `fun interface` instead of `suspend () -> String`: Dagger sees a
 * Kotlin suspend lambda as `Function1<Continuation<String>, ?>`, whose wildcard
 * does not match the provider binding. A named interface keeps the binding
 * unambiguous and still lets tests pass a lambda.
 */
fun interface ClientRuleSetVersionProvider {
    suspend fun version(): String
}
