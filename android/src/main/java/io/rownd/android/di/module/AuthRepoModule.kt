package io.rownd.android.di.module

import dagger.Module
import dagger.Provides
import io.rownd.android.models.repos.AuthRepo
import io.rownd.android.models.repos.SignInRepo
import io.rownd.android.models.repos.StateRepo
import io.rownd.android.models.repos.UserRepo
import io.rownd.android.util.AuthenticatedApiClient
import io.rownd.android.util.LegacyTokenApiClient
import io.rownd.android.util.RowndContext

@Module
class AuthRepoModule {
    @Provides fun provideAuthRepo(
        rowndContext: RowndContext,
        stateRepo: StateRepo,
        userRepo: UserRepo,
        signInRepo: SignInRepo,
        legacyTokenApiClient: LegacyTokenApiClient,
        authenticatedApiClient: AuthenticatedApiClient
    ): AuthRepo {
        val authRepo = AuthRepo()
        authRepo.rowndContext = rowndContext
        authRepo.stateRepo = stateRepo
        authRepo.userRepo = userRepo
        authRepo.signInRepo = signInRepo
        authRepo.legacyTokenApiClient = legacyTokenApiClient
        authRepo.authenticatedApiClient = authenticatedApiClient
        return authRepo
    }
}