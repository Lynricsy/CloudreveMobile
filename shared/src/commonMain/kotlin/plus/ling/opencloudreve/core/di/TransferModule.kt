package plus.ling.opencloudreve.core.di

import org.koin.dsl.module
import plus.ling.opencloudreve.core.platform.PlatformContext
import plus.ling.opencloudreve.core.transfer.ChunkMaterializer
import plus.ling.opencloudreve.core.transfer.DownloadPlanner
import plus.ling.opencloudreve.core.transfer.FileSystemBridge
import plus.ling.opencloudreve.core.transfer.TransferExecutorHolder
import plus.ling.opencloudreve.core.transfer.TransferManager
import plus.ling.opencloudreve.core.transfer.TransferRepository
import plus.ling.opencloudreve.core.transfer.UploadPlanner

/**
 * 传输分片的依赖：需要容器中已有 `OpenCloudreveDatabase`、`ApiClientProvider`（authModule）与 `PlatformContext`（CoreGraph）。
 * 平台执行器通过 `get<TransferExecutorHolder>().install(executor)` 安装。
 */
val transferModule = module {
    single<FileSystemBridge> { get<PlatformContext>().fileSystem }
    single { TransferRepository(get()) }
    single { UploadPlanner(get(), get(), get()) }
    single { DownloadPlanner(get(), get()) }
    single { ChunkMaterializer(get(), get(), get()) }
    single { TransferExecutorHolder() }
    single { TransferManager(get(), get(), get(), get()) }
}
