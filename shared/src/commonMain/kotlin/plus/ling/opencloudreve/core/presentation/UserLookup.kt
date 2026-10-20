package plus.ling.opencloudreve.core.presentation

import kotlinx.coroutines.CancellationException
import plus.ling.opencloudreve.core.model.User
import plus.ling.opencloudreve.core.settings.UserSettingsRepository

/** 当前用户（优先用缓存，否则拉取）；取不到返回 null，权限判定交给服务端 */
internal suspend fun UserSettingsRepository.currentUserOrNull(): User? =
    user.value ?: try {
        me()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
