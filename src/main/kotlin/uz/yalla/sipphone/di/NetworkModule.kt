package uz.yalla.sipphone.di

import org.koin.dsl.module
import uz.yalla.sipphone.data.auth.AuthEventBus
import uz.yalla.sipphone.data.auth.InMemoryTokenProvider
import uz.yalla.sipphone.data.auth.TokenProvider
import uz.yalla.sipphone.data.network.createHttpClient
import uz.yalla.sipphone.data.settings.AppSettings

val networkModule = module {
    single<TokenProvider> { InMemoryTokenProvider() }
    single { AuthEventBus() }
    single {
        val settings: AppSettings = get()
        createHttpClient(
            baseUrlProvider = { settings.backendUrl },
            tokenProvider = get(),
        )
    }
}
