package com.boardgamenation.tracker.di

import com.boardgamenation.tracker.BuildConfig
import com.boardgamenation.tracker.data.bgg.BggApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * Identifies the app to BGG. A descriptive User-Agent is the difference between a
     * request an operator can attribute and one they can only block.
     */
    private const val USER_AGENT =
        "BoardGameNation/1.0 (personal collection tracker; Android; non-commercial)"

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient {
        val headers = Interceptor { chain ->
            val builder = chain.request().newBuilder()
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/xml, application/xml")

            // The token lives in local.properties and reaches the app through
            // BuildConfig. When it is absent the header is simply omitted; the
            // repository refuses the call before it gets this far.
            if (BuildConfig.BGG_CONFIGURED) {
                builder.header("Authorization", "Bearer ${BuildConfig.BGG_API_TOKEN}")
            }
            chain.proceed(builder.build())
        }

        return OkHttpClient.Builder()
            .addInterceptor(headers)
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(
                        HttpLoggingInterceptor().apply {
                            // BASIC, not BODY: a thing response is enormous and a token
                            // has no business in logcat.
                            level = HttpLoggingInterceptor.Level.BASIC
                        },
                    )
                }
            }
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient): Retrofit = Retrofit.Builder()
        // No leading www, as the API policy requires.
        .baseUrl(BuildConfig.BGG_BASE_URL)
        .client(client)
        .build()

    @Provides
    @Singleton
    fun provideBggApi(retrofit: Retrofit): BggApi = retrofit.create(BggApi::class.java)
}
