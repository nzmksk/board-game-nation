package com.boardgamenation.tracker.di

import android.content.Context
import android.os.SystemClock
import androidx.work.WorkManager
import com.boardgamenation.tracker.core.time.AppClock
import com.boardgamenation.tracker.core.time.ElapsedTimeSource
import com.boardgamenation.tracker.core.time.SystemAppClock
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/** Application-scoped scope for work that must outlive any one screen. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindClock(impl: SystemAppClock): AppClock

    companion object {

        /**
         * Monotonic since boot. This is the only place in the app that reads it, so the
         * timer engine itself stays a pure function of the deltas it is handed.
         */
        @Provides
        @Singleton
        fun provideElapsedTimeSource(): ElapsedTimeSource = object : ElapsedTimeSource {
            override fun elapsedMillis(): Long = SystemClock.elapsedRealtime()
        }

        @Provides
        @IoDispatcher
        fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

        @Provides
        @DefaultDispatcher
        fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

        @Provides
        @Singleton
        @ApplicationScope
        fun provideApplicationScope(
            @DefaultDispatcher dispatcher: CoroutineDispatcher,
        ): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

        @Provides
        @Singleton
        fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
            WorkManager.getInstance(context)
    }
}
