package uz.yalla.sipphone.di

import org.koin.dsl.module
import uz.yalla.sipphone.navigation.ComponentFactory
import uz.yalla.sipphone.navigation.ComponentFactoryImpl

val featureModule = module {
    single<ComponentFactory> {
        ComponentFactoryImpl(
            authRepository = get(),
            sipAccountManager = get(),
            appSettings = get(),
            callEngine = get(),
            jcefManager = get(),
            eventEmitter = get(),
            security = get(),
            auditLog = get(),
            updateManager = get(),
        )
    }
}
