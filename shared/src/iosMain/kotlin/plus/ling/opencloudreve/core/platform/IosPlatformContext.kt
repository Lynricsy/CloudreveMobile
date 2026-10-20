package plus.ling.opencloudreve.core.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.fileHandleForReadingFromURL
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.writeData
import platform.Foundation.writeToFile
import platform.posix.memcpy
import plus.ling.opencloudreve.core.db.DatabaseDriverFactory
import plus.ling.opencloudreve.core.transfer.FileSystemBridge
import kotlin.io.encoding.Base64

/**
 * iOS 平台上下文。
 * @param appGroupId App Group（`group.plus.ling.opencloudreve`），数据库与临时目录放在其容器内
 * @param keychainAccessGroup 完整 Keychain 访问组（`<TeamID>.plus.ling.opencloudreve.shared`）；单元测试宿主传 null
 */
class IosPlatformContext(
    appGroupId: String,
    keychainAccessGroup: String?,
    override val appVersion: String,
) : PlatformContext {
    override val databaseDriverFactory = DatabaseDriverFactory(appGroupId)
    override val secureStore: SecretStore = SecureStore(keychainAccessGroup)
    override val fileSystem: FileSystemBridge = IosFileSystemBridge(appGroupId)
    override val platformName: String = PlatformContext.PLATFORM_IOS
}

/**
 * iOS 文件桥。`sourceRef` 为：
 * - 绝对路径（以 `/` 开头）或 `file://` URL（App Group `Inbox/` 中的副本等）；
 * - 否则视为 base64 编码的安全作用域书签（文件选择器得到的外部文件），读取期间 start/stopAccessingSecurityScopedResource。
 *
 * 临时目录位于 App Group 容器的 `tmp/`（主 App 与扩展共用），取不到容器时退回 `NSTemporaryDirectory()`。
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosFileSystemBridge(private val appGroupId: String) : FileSystemBridge {
    private val fm get() = NSFileManager.defaultManager

    @Throws(Exception::class)
    override suspend fun size(sourceRef: String): Long? = withContext(Dispatchers.IO) {
        val url = resolve(sourceRef) ?: return@withContext null
        withAccess(url) {
            val path = url.path ?: return@withAccess null
            val attrs = fm.attributesOfItemAtPath(path, null) ?: return@withAccess null
            (attrs[NSFileSize] as? NSNumber)?.longLongValue
        }
    }

    @Throws(Exception::class)
    override suspend fun readRange(sourceRef: String, offset: Long, length: Int): ByteArray =
        withContext(Dispatchers.IO) {
            val url = resolve(sourceRef) ?: throw IllegalStateException("Source unavailable: $sourceRef")
            withAccess(url) {
                memScoped {
                    val err = alloc<ObjCObjectVar<NSError?>>()
                    val handle = NSFileHandle.fileHandleForReadingFromURL(url, err.ptr)
                        ?: throw IllegalStateException("Open failed: ${err.value?.localizedDescription}")
                    try {
                        if (!handle.seekToOffset(offset.convert(), err.ptr)) {
                            throw IllegalStateException("Seek failed: ${err.value?.localizedDescription}")
                        }
                        val data = handle.readDataUpToLength(length.convert(), err.ptr)
                            ?: throw IllegalStateException("Read failed: ${err.value?.localizedDescription}")
                        data.toByteArray()
                    } finally {
                        handle.closeAndReturnError(null)
                    }
                }
            }
        }

    override fun tempDir(): String {
        val base = fm.containerURLForSecurityApplicationGroupIdentifier(appGroupId)?.path
            ?.let { "$it/tmp" }
            ?: NSTemporaryDirectory().trimEnd('/')
        fm.createDirectoryAtPath(base, withIntermediateDirectories = true, attributes = null, error = null)
        return base
    }

    @Throws(Exception::class)
    override suspend fun writeFile(path: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        ensureParent(path)
        if (!bytes.toNSData().writeToFile(path, atomically = true)) {
            throw IllegalStateException("Write failed: $path")
        }
    }

    @Throws(Exception::class)
    override suspend fun appendFile(path: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        ensureParent(path)
        if (!fm.fileExistsAtPath(path) && !fm.createFileAtPath(path, contents = null, attributes = null)) {
            throw IllegalStateException("Create failed: $path")
        }
        val handle = NSFileHandle.fileHandleForWritingAtPath(path) ?: throw IllegalStateException("Open failed: $path")
        memScoped {
            val err = alloc<ObjCObjectVar<NSError?>>()
            try {
                if (!handle.seekToEndReturningOffset(null, err.ptr) || !handle.writeData(bytes.toNSData(), err.ptr)) {
                    throw IllegalStateException("Append failed: ${err.value?.localizedDescription}")
                }
            } finally {
                handle.closeAndReturnError(null)
            }
        }
    }

    @Throws(Exception::class)
    override suspend fun delete(path: String) {
        withContext(Dispatchers.IO) {
            if (fm.fileExistsAtPath(path)) fm.removeItemAtPath(path, null)
        }
    }

    private fun ensureParent(path: String) {
        val parent = path.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) {
            fm.createDirectoryAtPath(parent, withIntermediateDirectories = true, attributes = null, error = null)
        }
    }

    /** 解析 sourceRef；书签失效时返回 null */
    private fun resolve(ref: String): NSURL? = when {
        ref.startsWith("/") -> NSURL.fileURLWithPath(ref)
        ref.startsWith("file://") -> NSURL.URLWithString(ref)
        else -> memScoped {
            val bytes = try {
                Base64.decode(ref)
            } catch (_: IllegalArgumentException) {
                return@memScoped null
            }
            val stale = alloc<BooleanVar>()
            NSURL.URLByResolvingBookmarkData(
                bookmarkData = bytes.toNSData(),
                options = 0u,
                relativeToURL = null,
                bookmarkDataIsStale = stale.ptr,
                error = null,
            )
        }
    }

    /** 在安全作用域访问期间执行 [block]（普通路径的 start 返回 false，不影响读取） */
    private inline fun <T> withAccess(url: NSURL, block: () -> T): T {
        val started = url.startAccessingSecurityScopedResource()
        try {
            return block()
        } finally {
            if (started) url.stopAccessingSecurityScopedResource()
        }
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { NSData.create(bytes = it.addressOf(0), length = size.convert()) }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val len = length.toInt()
    if (len == 0) return ByteArray(0)
    val out = ByteArray(len)
    out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}
