package plus.ling.opencloudreve.core.presentation.files

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import plus.ling.opencloudreve.core.api.CloudreveJson
import plus.ling.opencloudreve.core.files.FileRepository
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.model.CustomPropDefinition
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.model.MetadataKeys
import plus.ling.opencloudreve.core.model.MetadataPatch
import plus.ling.opencloudreve.core.model.NavigatorCapability
import plus.ling.opencloudreve.core.model.User
import plus.ling.opencloudreve.core.presentation.CoreViewModel
import plus.ling.opencloudreve.core.presentation.UiError
import plus.ling.opencloudreve.core.presentation.currentUserOrNull
import plus.ling.opencloudreve.core.presentation.toUiError
import plus.ling.opencloudreve.core.settings.UserSettingsRepository
import plus.ling.opencloudreve.core.site.SiteConfigRepository

/** 标签（元数据 `tag:<name>`，值为颜色，可为空） */
data class FileTag(val name: String, val color: String? = null)

/** 自定义属性值校验错误（与服务端 `manager/metadata.go` 规则一致） */
enum class PropValidationError { TOO_SHORT, TOO_LONG, NOT_A_NUMBER, TOO_SMALL, TOO_LARGE, INVALID_OPTION, NOT_BOOLEAN, UNKNOWN_PROP }

/** 文件上已设置的自定义属性（`props:<id>`）；[definition] 为 null 表示站点已删除该定义 */
data class CustomPropEntry(
    val id: String,
    val definition: CustomPropDefinition?,
    val value: String,
) {
    val type: String get() = definition?.type.orEmpty()
    val name: String get() = definition?.name ?: id
    val booleanValue: Boolean get() = CustomPropCodec.isTrue(value)
    val numberValue: Long? get() = value.toLongOrNull()

    /** 评分（0 表示未评） */
    val ratingValue: Int get() = value.toIntOrNull() ?: 0
    val multiSelectValues: List<String> get() = CustomPropCodec.decodeMultiSelect(value)
}

/** 自定义属性值的编码与校验（平台把控件值转成元数据字符串） */
object CustomPropCodec {
    fun encodeBoolean(value: Boolean): String = value.toString()
    fun encodeRating(value: Int?): String = value?.takeIf { it > 0 }?.toString().orEmpty()
    fun encodeMultiSelect(values: List<String>): String =
        CloudreveJson.encodeToString(ListSerializer(String.serializer()), values)

    fun decodeMultiSelect(value: String): List<String> = if (value.isBlank()) emptyList() else
        runCatching { CloudreveJson.decodeFromString(ListSerializer(String.serializer()), value) }.getOrDefault(emptyList())

    /** 与网页端 `isTrueVal` 一致 */
    fun isTrue(value: String): Boolean = value == "true" || value == "1" || value == "yes" || value == "on"

    /** 本地预校验；[definition] 为 null 时返回 [PropValidationError.UNKNOWN_PROP] */
    fun validate(definition: CustomPropDefinition?, value: String): PropValidationError? {
        val def = definition ?: return PropValidationError.UNKNOWN_PROP
        val len = value.encodeToByteArray().size
        return when (def.type) {
            CustomPropDefinition.TYPE_TEXT -> when {
                def.min > 0 && len < def.min -> PropValidationError.TOO_SHORT
                def.max > 0 && len > def.max -> PropValidationError.TOO_LONG
                else -> null
            }
            CustomPropDefinition.TYPE_LINK -> when {
                value.isEmpty() -> null
                def.min > 0 && len < def.min -> PropValidationError.TOO_SHORT
                def.max > 0 && len > def.max -> PropValidationError.TOO_LONG
                else -> null
            }
            CustomPropDefinition.TYPE_RATING -> when {
                value.isEmpty() -> null
                value.toIntOrNull() == null -> PropValidationError.NOT_A_NUMBER
                value.toInt() > def.max -> PropValidationError.TOO_LARGE
                else -> null
            }
            CustomPropDefinition.TYPE_NUMBER -> when {
                value.isEmpty() -> null
                value.toIntOrNull() == null -> PropValidationError.NOT_A_NUMBER
                value.toInt() < def.min -> PropValidationError.TOO_SMALL
                def.max > 0 && value.toInt() > def.max -> PropValidationError.TOO_LARGE
                else -> null
            }
            CustomPropDefinition.TYPE_BOOLEAN ->
                if (value.isEmpty() || value == "true" || value == "false") null else PropValidationError.NOT_BOOLEAN
            CustomPropDefinition.TYPE_SELECT ->
                if (value.isEmpty() || value in def.options) null else PropValidationError.INVALID_OPTION
            CustomPropDefinition.TYPE_MULTI_SELECT -> when {
                value.isEmpty() -> null
                runCatching { CloudreveJson.decodeFromString(ListSerializer(String.serializer()), value) }.getOrNull()
                    ?.all { it in def.options } != true -> PropValidationError.INVALID_OPTION
                else -> null
            }
            else -> null
        }
    }
}

data class MetadataEditState(
    val uris: List<String>,
    val files: List<FileItem> = emptyList(),
    val loading: Boolean = false,
    val error: UiError? = null,
    val saving: Boolean = false,
    /** 标签草稿（多选时为各文件标签的并集） */
    val tags: List<FileTag> = emptyList(),
    /** 草稿与服务端不同，需要 SaveTags */
    val tagsDirty: Boolean = false,
    val customPropDefinitions: List<CustomPropDefinition> = emptyList(),
    /** 单个文件时已设置的自定义属性 */
    val props: List<CustomPropEntry> = emptyList(),
    /** 最近一次提交被本地校验拒绝的属性 */
    val propErrors: Map<String, PropValidationError> = emptyMap(),
    /** emoji 预设：分类图标 → emoji 列表 */
    val emojiPresets: Map<String, List<String>> = emptyMap(),
    /** 当前 emoji 图标（多选时取第一个文件） */
    val emoji: String? = null,
    /** 当前图标颜色（仅目录） */
    val iconColor: String? = null,
    /** 当前用户（判断管理员可编辑他人文件） */
    val user: User? = null,
    /** 用户类型属性：搜索结果 */
    val userSearchResults: List<User> = emptyList(),
    val searchingUsers: Boolean = false,
    /** 用户类型属性：已解析的用户（id → 用户） */
    val resolvedUsers: Map<String, User> = emptyMap(),
) {
    val isSingle: Boolean get() = files.size == 1

    /** 可编辑：每个文件可更新（自己的，或管理员在“我的文件”）且有 update_metadata 能力，且不在分享文件系统 */
    val canEdit: Boolean
        get() = files.isNotEmpty() && files.all { f ->
            val fs = CrUri.parseOrNull(f.path)?.fs
            val updatable = fs != CrUri.FS_SHARE && (f.owned || (user?.isAdmin == true && fs == CrUri.FS_MY))
            updatable && f.can(NavigatorCapability.UPDATE_METADATA)
        }

    /** 图标颜色仅对目录（全部为目录时） */
    val canChangeIconColor: Boolean get() = canEdit && files.all { it.isFolder }

    /** 自定义属性仅对单个文件 */
    val canEditProps: Boolean get() = canEdit && isSingle

    /** 尚未添加的属性定义 */
    val availableProps: List<CustomPropDefinition>
        get() = customPropDefinitions.filter { def -> props.none { it.id == def.id } }

    /** 预设颜色（与网页端一致） */
    val presetColors: List<String> get() = PRESET_COLORS

    companion object {
        val PRESET_COLORS: List<String> = listOf(
            "#d50000", "#f44336", "#e91e63", "#f48fb1", "#9c27b0", "#ce93d8", "#673ab7", "#3f51b5", "#9fa8da",
            "#2196f3", "#0d47a1", "#00bcd4", "#80deea", "#009688", "#80cbc4", "#1b5e20", "#4caf50", "#a5d6a7",
            "#cddc39", "#ffeb3b", "#ffc107", "#ff9800", "#ff5722", "#795548", "#9e9e9e", "#607d8b",
        )
    }
}

sealed interface MetadataEditIntent {
    data object Refresh : MetadataEditIntent

    /** 草稿：加标签（重名忽略） */
    data class AddTag(val name: String, val color: String? = null) : MetadataEditIntent
    data class RemoveTag(val name: String) : MetadataEditIntent
    data class SetTagColor(val name: String, val color: String?) : MetadataEditIntent

    /** 提交标签草稿：设置草稿中的标签，删除文件上有但草稿中没有的标签 */
    data object SaveTags : MetadataEditIntent
    data object ResetTags : MetadataEditIntent

    /** 立即生效：设置/清除 emoji 图标（须在站点预设中） */
    data class SetEmoji(val emoji: String?) : MetadataEditIntent

    /** 立即生效：设置/清除目录图标颜色 */
    data class SetIconColor(val color: String?) : MetadataEditIntent

    /** 添加属性（使用定义的默认值） */
    data class AddProp(val id: String) : MetadataEditIntent

    /** 设置属性值（编码见 [CustomPropCodec]）；本地校验失败写入 `propErrors` 不提交 */
    data class SetProp(val id: String, val value: String) : MetadataEditIntent
    data class RemoveProp(val id: String) : MetadataEditIntent

    /** 用户类型属性：按关键字搜索用户 */
    data class SearchUsers(val keyword: String) : MetadataEditIntent
}

sealed interface MetadataEditEffect {
    /** 已保存（列表可刷新） */
    data object Updated : MetadataEditEffect

    /** 保存失败（锁冲突时 kind 为 LockConflict） */
    data class Failed(val error: UiError) : MetadataEditEffect
}

/**
 * 标签 / 自定义属性 / 图标（emoji、目录颜色）编辑（`PATCH /file/metadata`）。
 * 标签支持多选批量，按网页端规则提交差异；自定义属性仅单个文件，提交前按服务端规则本地校验。
 */
class MetadataEditViewModel(
    val accountId: String,
    uris: List<String>,
    private val files: FileRepository,
    private val site: SiteConfigRepository,
    private val userSettings: UserSettingsRepository,
) : CoreViewModel<MetadataEditState, MetadataEditIntent, MetadataEditEffect>(MetadataEditState(uris = uris)) {

    private var loadJob: Job? = null
    private var searchJob: Job? = null

    init {
        scope.launch {
            val explorer = try {
                site.explorer(accountId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                site.cachedExplorer(accountId)
            }
            val emojis = try {
                site.emojis(accountId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                site.cachedEmojis(accountId)
            }
            updateState {
                it.copy(
                    customPropDefinitions = explorer?.customProps.orEmpty(),
                    emojiPresets = emojis?.parsedPreset().orEmpty(),
                ).withProps()
            }
        }
        scope.launch {
            val me = userSettings.currentUserOrNull()
            updateState { it.copy(user = me) }
        }
        load(showLoading = true)
    }

    override fun on(intent: MetadataEditIntent) {
        when (intent) {
            MetadataEditIntent.Refresh -> load(showLoading = false)
            is MetadataEditIntent.AddTag -> {
                val name = intent.name.trim()
                if (name.isEmpty() || currentState.tags.any { it.name == name }) return
                updateState { it.copy(tags = it.tags + FileTag(name, intent.color?.ifEmpty { null }), tagsDirty = true) }
            }
            is MetadataEditIntent.RemoveTag -> updateState { s ->
                s.copy(tags = s.tags.filterNot { it.name == intent.name }, tagsDirty = true)
            }
            is MetadataEditIntent.SetTagColor -> updateState { s ->
                s.copy(
                    tags = s.tags.map { if (it.name == intent.name) it.copy(color = intent.color?.ifEmpty { null }) else it },
                    tagsDirty = true,
                )
            }
            MetadataEditIntent.SaveTags -> saveTags()
            MetadataEditIntent.ResetTags -> updateState { it.copy(tags = tagsOf(it.files), tagsDirty = false) }
            is MetadataEditIntent.SetEmoji -> patch(listOf(optionalPatch(MetadataKeys.EMOJI, intent.emoji)))
            is MetadataEditIntent.SetIconColor -> {
                if (!currentState.canChangeIconColor) return
                patch(listOf(optionalPatch(MetadataKeys.ICON_COLOR, intent.color)))
            }
            is MetadataEditIntent.AddProp -> {
                val def = currentState.customPropDefinitions.firstOrNull { it.id == intent.id } ?: return
                setProp(def.id, def.default.orEmpty())
            }
            is MetadataEditIntent.SetProp -> setProp(intent.id, intent.value)
            is MetadataEditIntent.RemoveProp -> {
                if (!currentState.canEditProps) return
                updateState { it.copy(propErrors = it.propErrors - intent.id) }
                patch(listOf(MetadataPatch(MetadataKeys.CUSTOM_PROPS_PREFIX + intent.id, remove = true)))
            }
            is MetadataEditIntent.SearchUsers -> searchUsers(intent.keyword)
        }
    }

    private fun optionalPatch(key: String, value: String?): MetadataPatch =
        if (value.isNullOrEmpty()) MetadataPatch(key, remove = true) else MetadataPatch(key, value)

    private fun load(showLoading: Boolean) {
        loadJob?.cancel()
        loadJob = scope.launch {
            if (showLoading) updateState { it.copy(loading = true, error = null) }
            try {
                fetch(resetTags = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val err = e.toUiError()
                updateState { it.copy(loading = false, error = if (it.files.isEmpty()) err else null) }
                if (currentState.files.isNotEmpty()) sendEffect(MetadataEditEffect.Failed(err))
            }
        }
    }

    private suspend fun fetch(resetTags: Boolean) {
        val loaded = currentState.uris.map { files.info(it) }
        updateState { s ->
            val first = loaded.firstOrNull()
            s.copy(
                files = loaded,
                loading = false,
                error = null,
                tags = if (resetTags || !s.tagsDirty) tagsOf(loaded) else s.tags,
                tagsDirty = if (resetTags) false else s.tagsDirty,
                emoji = first?.metadata?.get(MetadataKeys.EMOJI),
                iconColor = first?.metadata?.get(MetadataKeys.ICON_COLOR),
            ).withProps()
        }
        resolveUsers()
    }

    /** 各文件标签的并集（保持首次出现顺序与颜色） */
    private fun tagsOf(list: List<FileItem>): List<FileTag> {
        val result = LinkedHashMap<String, FileTag>()
        list.forEach { f ->
            f.metadata.forEach { (k, v) ->
                if (k.startsWith(MetadataKeys.TAG_PREFIX)) {
                    val name = k.removePrefix(MetadataKeys.TAG_PREFIX)
                    if (name.isNotEmpty() && name !in result) result[name] = FileTag(name, v.ifEmpty { null })
                }
            }
        }
        return result.values.toList()
    }

    private fun MetadataEditState.withProps(): MetadataEditState {
        val file = files.singleOrNull() ?: return copy(props = emptyList())
        val entries = file.metadata
            .filterKeys { it.startsWith(MetadataKeys.CUSTOM_PROPS_PREFIX) }
            .map { (k, v) ->
                val id = k.removePrefix(MetadataKeys.CUSTOM_PROPS_PREFIX)
                CustomPropEntry(id, customPropDefinitions.firstOrNull { it.id == id }, v)
            }
            .sortedBy { e -> customPropDefinitions.indexOfFirst { it.id == e.id }.let { if (it < 0) Int.MAX_VALUE else it } }
        return copy(props = entries)
    }

    private fun saveTags() {
        val s = currentState
        if (!s.canEdit || s.saving) return
        val draft = s.tags.map { it.name }.toSet()
        val patches = s.tags.map { MetadataPatch(MetadataKeys.TAG_PREFIX + it.name, it.color.orEmpty()) } +
            s.files.flatMap { f -> f.metadata.keys.filter { it.startsWith(MetadataKeys.TAG_PREFIX) } }
                .map { it.removePrefix(MetadataKeys.TAG_PREFIX) }
                .filter { it.isNotEmpty() && it !in draft }
                .distinct()
                .map { MetadataPatch(MetadataKeys.TAG_PREFIX + it, remove = true) }
        if (patches.isEmpty()) {
            updateState { it.copy(tagsDirty = false) }
            return
        }
        patch(patches, resetTags = true)
    }

    private fun setProp(id: String, value: String) {
        val s = currentState
        if (!s.canEditProps) return
        val def = s.customPropDefinitions.firstOrNull { it.id == id }
        val error = CustomPropCodec.validate(def, value)
        if (error != null) {
            updateState { it.copy(propErrors = it.propErrors + (id to error)) }
            return
        }
        updateState { it.copy(propErrors = it.propErrors - id) }
        patch(listOf(MetadataPatch(MetadataKeys.CUSTOM_PROPS_PREFIX + id, value)))
    }

    private fun patch(patches: List<MetadataPatch>, resetTags: Boolean = false) {
        val s = currentState
        if (!s.canEdit || s.saving) return
        scope.launch {
            updateState { it.copy(saving = true) }
            try {
                files.patchMetadata(s.uris, patches)
                fetch(resetTags = resetTags)
                updateState { it.copy(saving = false) }
                sendEffect(MetadataEditEffect.Updated)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateState { it.copy(saving = false) }
                sendEffect(MetadataEditEffect.Failed(e.toUiError()))
            }
        }
    }

    private fun searchUsers(keyword: String) {
        searchJob?.cancel()
        val q = keyword.trim()
        if (q.isEmpty()) {
            updateState { it.copy(userSearchResults = emptyList(), searchingUsers = false) }
            return
        }
        searchJob = scope.launch {
            updateState { it.copy(searchingUsers = true) }
            try {
                val users = userSettings.searchUsers(q)
                updateState { s ->
                    s.copy(
                        userSearchResults = users,
                        searchingUsers = false,
                        resolvedUsers = s.resolvedUsers + users.associateBy { it.id },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateState { it.copy(searchingUsers = false) }
                sendEffect(MetadataEditEffect.Failed(e.toUiError()))
            }
        }
    }

    /** 解析用户类型属性值对应的用户（失败忽略，显示原始 id） */
    private suspend fun resolveUsers() {
        val ids = currentState.props
            .filter { it.type == CustomPropDefinition.TYPE_USER && it.value.isNotEmpty() && it.value !in currentState.resolvedUsers }
            .map { it.value }
            .distinct()
        for (id in ids) {
            val user = try {
                userSettings.userInfo(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: continue
            updateState { it.copy(resolvedUsers = it.resolvedUsers + (id to user)) }
        }
    }
}
