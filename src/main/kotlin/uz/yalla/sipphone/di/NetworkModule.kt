package uz.yalla.sipphone.di

import org.koin.dsl.module
import uz.yalla.sipphone.data.auth.AuthEventBus
import uz.yalla.sipphone.data.auth.InMemoryTokenProvider
import uz.yalla.sipphone.data.auth.TokenProvider
import uz.yalla.sipphone.data.network.createHttpClient

val networkModule = module {
    single<TokenProvider> { InMemoryTokenProvider() }
    single { AuthEventBus() }
    single { createHttpClient(tokenProvider = get()) }
}
