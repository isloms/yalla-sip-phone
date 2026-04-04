package uz.yalla.sipphone.di

import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import uz.yalla.sipphone.data.pjsip.PjsipBridge
import uz.yalla.sipphone.data.settings.AppSettings
import uz.yalla.sipphone.domain.CallEngine
import uz.yalla.sipphone.domain.RegistrationEngine

val appModule = module {
    single { PjsipBridge() }
    single<RegistrationEngine> { get<PjsipBridge>() }
    single<CallEngine> { get<PjsipBridge>() }
    singleOf(::AppSettings)
}
