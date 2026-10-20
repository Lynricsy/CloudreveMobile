package plus.ling.opencloudreve.ui.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.presentation.ErrorKind
import plus.ling.opencloudreve.core.presentation.auth.ServerSetupEffect
import plus.ling.opencloudreve.core.presentation.auth.ServerSetupIntent
import plus.ling.opencloudreve.core.presentation.auth.ServerSetupUiState
import plus.ling.opencloudreve.core.presentation.auth.ServerSetupViewModel
import plus.ling.opencloudreve.ui.common.CollectEffects
import plus.ling.opencloudreve.ui.common.message

@Composable
fun ServerSetupScreen(
    canGoBack: Boolean,
    onBack: () -> Unit,
    onProceed: (siteUrl: String) -> Unit,
    viewModel: ServerSetupViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CollectEffects(viewModel.effects) { effect ->
        when (effect) {
            is ServerSetupEffect.Proceed -> onProceed(effect.server.siteUrl)
        }
    }
    ServerSetupContent(state = state, canGoBack = canGoBack, onBack = onBack, onIntent = viewModel::on)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerSetupContent(
    state: ServerSetupUiState,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onIntent: (ServerSetupIntent) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    if (canGoBack) {
                        IconButton(onClick = onBack) {
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
                Spacer(Modifier.height(24.dp))
                Image(
                    imageVector = Icons.Outlined.Dns,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
                )
                Text(
                    text = stringResource(R.string.server_title),
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.server_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                val error = state.error
                OutlinedTextField(
                    value = state.url,
                    onValueChange = { onIntent(ServerSetupIntent.UrlChanged(it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.server_url_label)) },
                    placeholder = { Text(stringResource(R.string.server_url_placeholder)) },
                    singleLine = true,
                    enabled = !state.checking,
                    isError = error != null,
                    supportingText = {
                        Text(
                            when {
                                error == null -> stringResource(R.string.server_url_hint)
                                error.kind == ErrorKind.Validation && error.code == null ->
                                    stringResource(R.string.error_invalid_url)
                                else -> error.message()
                            },
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                        autoCorrectEnabled = false,
                    ),
                    keyboardActions = KeyboardActions(onGo = { onIntent(ServerSetupIntent.Submit) }),
                )
                Button(
                    onClick = { onIntent(ServerSetupIntent.Submit) },
                    enabled = state.canSubmit,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    if (state.checking) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.action_continue))
                    }
                }
            }
        }
    }
}
