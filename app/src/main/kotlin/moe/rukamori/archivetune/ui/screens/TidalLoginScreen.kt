package moe.rukamori.archivetune.ui.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.screens.settings.tidalFailureText
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.viewmodels.TidalLoginUiState
import moe.rukamori.archivetune.viewmodels.TidalLoginViewModel

const val TIDAL_LOGIN_ROUTE = "login/tidal"

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TidalLoginScreen(navController: NavController, viewModel: TidalLoginViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        if (state == TidalLoginUiState.Success) navController.navigateUp()
    }

    BackHandler(enabled = canGoBack) { webView?.goBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tidal_connect)) },
                navigationIcon = {
                    IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
                        Icon(painterResource(R.drawable.arrow_back), null)
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            Modifier
                .padding(top = innerPadding.calculateTopPadding())
                .consumeWindowInsets(PaddingValues(top = innerPadding.calculateTopPadding()))
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            val loginState = state
            val loginUrl = (loginState as? TidalLoginUiState.Authorizing)?.verificationUrl

            when {
                loginState is TidalLoginUiState.Error -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(tidalFailureText(loginState.failure))
                    TextButton(onClick = viewModel::retry) { Text(stringResource(R.string.retry)) }
                }

                loginUrl == null -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.tidal_login_loading))
                }

                else -> AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            webView = this
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                allowFileAccess = false
                                allowContentAccess = false
                                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            }
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.url.scheme != "https"

                                override fun onPageFinished(view: WebView, url: String) {
                                    canGoBack = view.canGoBack()
                                }

                                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                    if (request.isForMainFrame) viewModel.onWebFailure()
                                }
                            }
                            loadUrl(loginUrl)
                        }
                    },
                    onRelease = {
                        it.stopLoading()
                        it.destroy()
                        webView = null
                        canGoBack = false
                    },
                )
            }
        }
    }
}
