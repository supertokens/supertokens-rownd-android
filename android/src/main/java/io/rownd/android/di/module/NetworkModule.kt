package io.rownd.android.di.module

import com.supertokens.session.SuperTokensInterceptor
import dagger.Module
import dagger.Provides
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

@Module
class NetworkModule {
    @Provides fun provideHttpClientEngine(): HttpClientEngine {
        return OkHttp.create {
            addInterceptor(SuperTokensInterceptor())
        }
    }
}