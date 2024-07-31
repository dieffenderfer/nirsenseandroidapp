package com.dieff.aurelian

import android.content.Context
//import com.dieff.aurelian.foregroundService.data.db.MessageDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Singleton  // Specifies the lifetime of the dependency - the lifetime of the app
    @Provides   // annotation used to identify a function that will provide a dependency
    fun provideApplication(@ApplicationContext app: Context): BaseApp {
        return app as BaseApp
    }

    @ApplicationScope
    @Provides
    @Singleton // The SupervisorJob keeps other coroutines running when one is canceled, otherwise they're all cancelled
    fun provideApplicationScope() = CoroutineScope(SupervisorJob())
}

@Retention(AnnotationRetention.RUNTIME)
@Qualifier
annotation class ApplicationScope