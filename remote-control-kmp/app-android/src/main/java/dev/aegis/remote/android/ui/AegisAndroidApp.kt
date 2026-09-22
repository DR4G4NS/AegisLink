@file:Suppress("FunctionNaming")

package dev.aegis.remote.android.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.aegis.remote.android.home.AegisAndroidAppGraph
import dev.aegis.remote.android.home.AndroidHomeViewModel
import dev.aegis.remote.android.home.AndroidHomeViewModelFactory
import dev.aegis.remote.android.ui.navigation.AndroidHomeScreen
import dev.aegis.remote.android.ui.theme.AegisTheme
import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel

@Composable
fun AegisAndroidApp(viewModel: AndroidHomeViewModel? = null) {
    val application = LocalContext.current.applicationContext as Application
    val factory = remember(application) { AndroidHomeViewModelFactory(AegisAndroidAppGraph(application)) }
    val homeViewModel = viewModel ?: composeViewModel<AndroidHomeViewModel>(factory = factory)
    val state by homeViewModel.state.collectAsState()
    AegisTheme {
        AndroidHomeScreen(
            state = state,
            dispatch = homeViewModel::dispatch,
        )
    }
}
