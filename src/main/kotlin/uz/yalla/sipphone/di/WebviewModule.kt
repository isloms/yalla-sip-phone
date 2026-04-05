package uz.yalla.sipphone.di

import org.koin.dsl.module
import uz.yalla.sipphone.data.jcef.BridgeAuditLog
import uz.yalla.sipphone.data.jcef.BridgeEventEmitter
import uz.yalla.sipphone.data.jcef.BridgeSecurity
import uz.yalla.sipphone.data.jcef.JcefManager

val webviewModule = module {
    single { JcefManager() }
    single { BridgeSecurity() }
    single { BridgeAuditLog() }
    single { BridgeEventEmitter(auditLog = get()) }
}
