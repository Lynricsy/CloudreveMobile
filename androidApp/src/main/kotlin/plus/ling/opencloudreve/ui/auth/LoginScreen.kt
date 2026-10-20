package plus.ling.opencloudreve.ui.auth

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.api.ErrorCode
import plus.ling.opencloudreve.core.auth.SiteUrl
import plus.ling.opencloudreve.core.presentation.auth.LoginEffect
import plus.ling.opencloudreve.core.presentation.auth.LoginIntent
import plus.ling.opencloudreve.core.presentation.auth.LoginNotice
import plus.ling.opencloudreve.core.presentation.auth.LoginParams
import plus.ling.opencloudreve.core.presentation.auth.LoginStage
import plus.ling.opencloudreve.core.presentation.auth.LoginUiState
import plus.ling.opencloudreve.core.presentation.auth.LoginViewModel
import plus.ling.opencloudreve.ui.common.CollectEffects
import plus.ling.opencloudreve.ui.common.message
import plus.ling.opencloudreve.ui.common.serverLanguage

@Composable
fun LoginScreen(
    siteUrl: String,
    email: String?,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onSignedIn: () -> Unit,
    onChangeServer: () -> Unit,
    viewModel: LoginViewModel = serverLanguage().let { language ->
        koinViewModel(parameters = { parametersOf(LoginParams(siteUrl, email, language)) })
    },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    CollectEffects(viewModel.effects) { effect ->
        when (effect) {
            is LoginEffect.LaunchPasskey -> scope.launch {
                viewModel.on(requestPasskey(context, effect.requestJson))
            }
            // 网页登录（OAuth）是 iOS 的 Passkey 路径；Android 使用原生 Passkey，不会发出该意图
            is LoginEffect.LaunchWebLogin -> viewModel.on(LoginIntent.WebLoginCancelled)
            is LoginEffect.SignedIn -> onSignedIn()
        }
    }
    LoginContent(
        state = state,
        canLeave = canGoBack,
        onLeave = onBack,
        onChangeServer = onChangeServer,
        onIntent = viewModel::on,
    )
}

/** 调起 Credential Manager，结果转为登录意图（`authenticationResponseJson` 原样提交） */
private suspend fun requestPasskey(context: Context, requestJson: String): LoginIntent = try {
    val response = CredentialManager.create(context).getCredential(
        context,
        GetCredentialRequest(listOf(GetPublicKeyCredentialOption(requestJson))),
    )
    when (val credential = response.credential) {
        is PublicKeyCredential -> LoginIntent.PasskeyCompleted(credential.authenticationResponseJson)
        else -> LoginIntent.PasskeyFailed(credential.type, cancelled = false)
    }
} catch (e: GetCredentialCancellationException) {
    LoginIntent.PasskeyFailed(e.message, cancelled = true)
} catch (_: NoCredentialException) {
    // 本机没有该站点的 Passkey（或没有可用的凭据提供方）
    LoginIntent.PasskeyFailed(context.getString(R.string.passkey_none), cancelled = false)
} catch (e: GetCredentialException) {
    LoginIntent.PasskeyFailed(e.errorMessage?.toString() ?: e.type, cancelled = false)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginContent(
    state: LoginUiState,
    canLeave: Boolean,
    onLeave: () -> Unit,
    onChangeServer: () -> Unit,
    onIntent: (LoginIntent) -> Unit,
) {
    BackHandler(enabled = state.canGoBack) { onIntent(LoginIntent.Back) }
    val snackbar = remember { SnackbarHostState() }
    val noticeText = state.notice?.let {
        stringResource(
            when (it) {
                LoginNotice.Registered -> R.string.register_done
                LoginNotice.RegisteredActivationRequired -> R.string.register_activation
                LoginNotice.ResetEmailSent -> R.string.forgot_sent
            },
        )
    }
    LaunchedEffect(noticeText) {
        if (noticeText != null) {
            snackbar.showSnackbar(noticeText)
            onIntent(LoginIntent.DismissNotice)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    if (state.canGoBack || canLeave) {
                        IconButton(onClick = { if (state.canGoBack) onIntent(LoginIntent.Back) else onLeave() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SiteHeader(state)
                when {
                    state.loadingConfig -> Box(Modifier.fillMaxWidth().height(160.dp), Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    state.configError != null -> ConfigError(state, onIntent, onChangeServer)
                    else -> when (state.stage) {
                        LoginStage.Email -> EmailStage(state, onIntent)
                        LoginStage.Password -> PasswordStage(state, onIntent)
                        LoginStage.TwoFactor -> TwoFactorStage(state, onIntent)
                        LoginStage.Register -> RegisterStage(state, onIntent)
                        LoginStage.ForgotPassword -> ForgotStage(state, onIntent)
                    }
                }
                if (!canLeave && state.stage == LoginStage.Email) {
                    TextButton(onClick = onChangeServer) { Text(stringResource(R.string.server_title)) }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SiteHeader(state: LoginUiState) {
    val logo = if (isSystemInDarkTheme()) state.logoLightUrl ?: state.logoUrl else state.logoUrl
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (logo != null) {
            AsyncImage(
                model = logo,
                contentDescription = null,
                modifier = Modifier.heightIn(max = 56.dp).widthIn(max = 220.dp),
            )
        }
        Text(
            text = when (state.stage) {
                LoginStage.TwoFactor -> stringResource(R.string.twofa_title)
                LoginStage.Register -> stringResource(R.string.register_title)
                LoginStage.ForgotPassword -> stringResource(R.string.forgot_title)
                else -> stringResource(R.string.login_title)
            },
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = listOf(state.siteTitle, SiteUrl.hostOf(state.siteUrl)).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ConfigError(state: LoginUiState, onIntent: (LoginIntent) -> Unit, onChangeServer: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.login_config_failed), style = MaterialTheme.typography.titleMedium)
        state.configError?.let { ErrorText(it.message()) }
        Button(onClick = { onIntent(LoginIntent.RetryConfig) }) { Text(stringResource(R.string.action_retry)) }
        TextButton(onClick = onChangeServer) { Text(stringResource(R.string.server_title)) }
    }
}

@Composable
private fun ErrorText(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 当前阶段的错误（邮箱阶段 404 表示邮箱未注册） */
@Composable
private fun StageError(state: LoginUiState) {
    val error = state.error ?: return
    ErrorText(
        if (state.stage == LoginStage.Email && error.code == ErrorCode.NOT_FOUND) {
            stringResource(R.string.login_email_not_found)
        } else {
            error.message()
        },
    )
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(48.dp)) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = ButtonDefaults.buttonColors().disabledContentColor,
            )
        } else {
            Text(text)
        }
    }
}

@Composable
private fun EmailField(state: LoginUiState, onIntent: (LoginIntent) -> Unit, imeAction: ImeAction, onIme: () -> Unit) {
    OutlinedTextField(
        value = state.email,
        onValueChange = { onIntent(LoginIntent.EmailChanged(it)) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.login_email)) },
        singleLine = true,
        enabled = !state.submitting,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = imeAction, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onAny = { onIme() }),
    )
}

@Composable
private fun PasswordField(
    value: String,
    label: String,
    enabled: Boolean,
    imeAction: ImeAction,
    onIme: () -> Unit,
    onChange: (String) -> Unit,
    supporting: String? = null,
    isError: Boolean = false,
    autoFocus: Boolean = false,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { focus.requestFocus() }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = isError,
        supportingText = supporting?.let { { Text(it) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        keyboardActions = KeyboardActions(onAny = { onIme() }),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    stringResource(if (visible) R.string.hide_password else R.string.show_password),
                )
            }
        },
    )
}

@Composable
private fun PasskeyButton(state: LoginUiState, onIntent: (LoginIntent) -> Unit) {
    // 原生 Passkey 需要服务端 ≥ 4.19.11（assetlinks 与 apk-key-hash origin）且站点启用 Passkey
    if (!state.enhanced || !state.sitePasskeyEnabled) return
    OutlinedButton(
        onClick = { onIntent(LoginIntent.StartPasskey) },
        enabled = !state.submitting,
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.login_passkey))
    }
}

@Composable
private fun Captcha(state: LoginUiState, onIntent: (LoginIntent) -> Unit, onSubmit: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    CaptchaSection(
        captcha = state.captcha,
        webUrl = state::captchaPageUrl,
        enabled = !state.submitting,
        onAnswerChange = { onIntent(LoginIntent.CaptchaAnswerChanged(it)) },
        onRefresh = { onIntent(LoginIntent.RefreshCaptcha) },
        onWebToken = { onIntent(LoginIntent.WebCaptchaSolved(it)) },
        onSubmit = onSubmit,
        onOpenWeb = { uriHandler.openUri(state.siteUrl + "/session") },
    )
}

@Composable
private fun EmailStage(state: LoginUiState, onIntent: (LoginIntent) -> Unit) {
    val next = { onIntent(LoginIntent.ContinueWithEmail) }
    EmailField(state, onIntent, ImeAction.Next, next)
    StageError(state)
    PrimaryButton(stringResource(R.string.action_next), state.canContinue, state.submitting, next)
    PasskeyButton(state, onIntent)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.registerEnabled) {
            TextButton(onClick = { onIntent(LoginIntent.OpenRegister) }) { Text(stringResource(R.string.login_register)) }
        }
        TextButton(onClick = { onIntent(LoginIntent.OpenForgotPassword) }) {
            Text(stringResource(R.string.login_forgot_password))
        }
    }
    Agreement(state)
}

@Composable
private fun EmailChip(state: LoginUiState, onIntent: (LoginIntent) -> Unit) {
    InputChip(
        selected = false,
        onClick = { onIntent(LoginIntent.Back) },
        label = { Text(state.email) },
        trailingIcon = { Text(stringResource(R.string.login_change_email), style = MaterialTheme.typography.labelMedium) },
    )
}

@Composable
private fun PasswordStage(state: LoginUiState, onIntent: (LoginIntent) -> Unit) {
    val submit = { onIntent(LoginIntent.SubmitPassword) }
    EmailChip(state, onIntent)
    if (!state.passwordEnabled) {
        ErrorText(stringResource(R.string.login_password_disabled))
    } else {
        PasswordField(
            value = state.password,
            label = stringResource(R.string.login_password),
            enabled = !state.submitting,
            imeAction = ImeAction.Done,
            onIme = submit,
            onChange = { onIntent(LoginIntent.PasswordChanged(it)) },
            autoFocus = state.password.isEmpty(),
        )
        Captcha(state, onIntent, submit)
        StageError(state)
        PrimaryButton(stringResource(R.string.login_sign_in), state.canSubmitPassword, state.submitting, submit)
    }
    if (state.userHasPasskey || !state.passwordEnabled) PasskeyButton(state, onIntent)
    TextButton(onClick = { onIntent(LoginIntent.OpenForgotPassword) }) {
        Text(stringResource(R.string.login_forgot_password))
    }
}

@Composable
private fun TwoFactorStage(state: LoginUiState, onIntent: (LoginIntent) -> Unit) {
    Text(
        text = stringResource(R.string.twofa_message),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
    )
    OtpField(
        value = state.otp,
        length = LoginUiState.OTP_LENGTH,
        enabled = !state.submitting,
        isError = state.error != null,
        contentDescription = stringResource(R.string.twofa_input),
        onValueChange = { onIntent(LoginIntent.OtpChanged(it)) },
    )
    StageError(state)
    PrimaryButton(stringResource(R.string.action_continue), state.canSubmitOtp, state.submitting) {
        onIntent(LoginIntent.SubmitOtp)
    }
}

@Composable
private fun RegisterStage(state: LoginUiState, onIntent: (LoginIntent) -> Unit) {
    val submit = { onIntent(LoginIntent.SubmitRegister) }
    EmailField(state, onIntent, ImeAction.Next) {}
    PasswordField(
        value = state.password,
        label = stringResource(R.string.login_password),
        enabled = !state.submitting,
        imeAction = ImeAction.Next,
        onIme = {},
        onChange = { onIntent(LoginIntent.PasswordChanged(it)) },
        supporting = stringResource(R.string.register_password_hint),
    )
    PasswordField(
        value = state.passwordConfirm,
        label = stringResource(R.string.login_password_confirm),
        enabled = !state.submitting,
        imeAction = ImeAction.Done,
        onIme = submit,
        onChange = { onIntent(LoginIntent.PasswordConfirmChanged(it)) },
        supporting = if (state.passwordMismatch) stringResource(R.string.register_password_mismatch) else null,
        isError = state.passwordMismatch,
    )
    Captcha(state, onIntent, submit)
    StageError(state)
    PrimaryButton(stringResource(R.string.register_submit), state.canSubmitRegister, state.submitting, submit)
    TextButton(onClick = { onIntent(LoginIntent.Back) }) { Text(stringResource(R.string.login_have_account)) }
    Agreement(state)
}

@Composable
private fun ForgotStage(state: LoginUiState, onIntent: (LoginIntent) -> Unit) {
    val submit = { onIntent(LoginIntent.SubmitForgotPassword) }
    Text(
        text = stringResource(R.string.forgot_message),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
    )
    EmailField(state, onIntent, ImeAction.Done, submit)
    Captcha(state, onIntent, submit)
    StageError(state)
    PrimaryButton(stringResource(R.string.forgot_submit), state.canSubmitForgot, state.submitting, submit)
}

/** 服务条款与隐私政策链接 */
@Composable
private fun Agreement(state: LoginUiState) {
    if (state.tosUrl == null && state.privacyPolicyUrl == null) return
    val uriHandler = LocalUriHandler.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.login_agreement),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.Center) {
            state.tosUrl?.let { url ->
                TextButton(onClick = { uriHandler.openUri(url) }) {
                    Text(stringResource(R.string.login_tos), style = MaterialTheme.typography.bodySmall)
                }
            }
            state.privacyPolicyUrl?.let { url ->
                TextButton(onClick = { uriHandler.openUri(url) }) {
                    Text(stringResource(R.string.login_privacy), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
