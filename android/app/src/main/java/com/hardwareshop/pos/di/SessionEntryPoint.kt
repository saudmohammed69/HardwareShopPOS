package com.hardwareshop.pos.di

import com.hardwareshop.pos.util.SessionHolder
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt entry point used to retrieve SessionHolder from composables that
 * cannot receive it through normal constructor injection.
 *
 * Usage:
 *   val session = EntryPointAccessors.fromApplication(
 *       context.applicationContext,
 *       SessionEntryPoint::class.java
 *   ).sessionHolder()
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SessionEntryPoint {
    fun sessionHolder(): SessionHolder
}
