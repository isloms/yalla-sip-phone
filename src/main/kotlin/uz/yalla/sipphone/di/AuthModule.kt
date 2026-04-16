package uz.yalla.sipphone.di

import org.koin.dsl.module
import uz.yalla.sipphone.data.auth.AuthApi
import uz.yalla.sipphone.data.auth.AuthRepositoryImpl
import uz.yalla.sipphone.data.auth.LogoutOrchestrator
import uz.yalla.sipphone.domain.AuthRepository

val authModule = module {
    single { AuthApi(client = get(), authEventBus = get()) }
    single<AuthRepository> { AuthRepositoryImpl(authApi = get(), tokenProvider = get(), appSettings = get()) }
    single { LogoutOrchestrator(get(), get(), get()) }
}
