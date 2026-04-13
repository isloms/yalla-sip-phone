package uz.yalla.sipphone.di

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.qualifier.named
import org.koin.dsl.module
import uz.yalla.sipphone.data.auth.ApiConfig
import uz.yalla.sipphone.data.settings.AppSettings
import uz.yalla.sipphone.data.update.MsiBootstrapperInstaller
import uz.yalla.sipphone.data.update.UpdateApi
import uz.yalla.sipphone.data.update.UpdateDownloader
import uz.yalla.sipphone.data.update.UpdateManager
import uz.yalla.sipphone.data.update.UpdatePaths
import uz.yalla.sipphone.data.update.asContract
import uz.yalla.sipphone.domain.CallEngine
import uz.yalla.sipphone.domain.update.UpdateChannel

/** Version constant — source of truth for `X-App-Version` header. Bump with each release. */
object BuildVersion {
    const val CURRENT: String = "1.0.0"
}

val updateModule = module {
    single(named("updaterScope")) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("updater"))
    }
    single { UpdatePaths() }
    single { UpdateApi(client = get<HttpClient>(), baseUrl = ApiConfig.BASE_URL) }
    single { UpdateDownloader(client = get(), paths = get()) }
    single { MsiBootstrapperInstaller() }
    single {
        val settings: AppSettings = get()
        UpdateManager(
            scope = get(named("updaterScope")),
            api = get<UpdateApi>().asContract(),
            downloader = get<UpdateDownloader>().asContract(),
            installer = get<MsiBootstrapperInstaller>().asContract(),
            paths = get(),
            callState = get<CallEngine>().callState,
            currentVersion = BuildVersion.CURRENT,
            channelProvider = { UpdateChannel.fromValue(settings.updateChannel) },
            installIdProvider = { settings.installId },
        )
    }
}
