package org.koin.core.component
interface KoinComponent
inline fun <reified T : Any> KoinComponent.inject(): Lazy<T> = lazy { throw NotImplementedError() }
