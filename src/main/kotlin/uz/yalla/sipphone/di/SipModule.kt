package uz.yalla.sipphone.di

import org.koin.dsl.module
import uz.yalla.sipphone.data.pjsip.PjsipEngine
import uz.yalla.sipphone.data.pjsip.PjsipSipAccountManager
import uz.yalla.sipphone.domain.CallEngine
import uz.yalla.sipphone.domain.SipAccountManager
import uz.yalla.sipphone.domain.SipStackLifecycle

val sipModule = module {
    single { PjsipEngine() }
    single<SipStackLifecycle> { get<PjsipEngine>() }
    single<CallEngine> { get<PjsipEngine>() }
    single<SipAccountManager> {
        PjsipSipAccountManager(
            accountManager = get<PjsipEngine>().accountManager,
            callEngine = get(),
            pjDispatcher = get<PjsipEngine>().pjDispatcher,
        )
    }
}
