package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.ai.configureAppCheck
import com.example.ui.screens.AssistantStageScreen
import com.example.ui.screens.PersonalVaultScreen
import com.example.ui.screens.PhoneHubScreen
import com.example.ui.screens.VoiceProfileScreen
import com.example.ui.theme.SaraTheme
import com.example.viewmodel.SaraViewModel

private sealed class SaraDestination(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
) {
    data object Sara : SaraDestination(
        route = "sara",
        labelRes = R.string.nav_sara,
        selectedIcon = Icons.Filled.GraphicEq,
        unselectedIcon = Icons.Outlined.GraphicEq,
        testTag = "nav_sara"
    )

    data object Hub : SaraDestination(
        route = "hub",
        labelRes = R.string.nav_actions,
        selectedIcon = Icons.Filled.PhoneAndroid,
        unselectedIcon = Icons.Outlined.PhoneAndroid,
        testTag = "nav_hub"
    )

    data object Personal : SaraDestination(
        route = "personal",
        labelRes = R.string.nav_personal,
        selectedIcon = Icons.Filled.Bookmark,
        unselectedIcon = Icons.Outlined.BookmarkBorder,
        testTag = "nav_personal"
    )

    data object Voice : SaraDestination(
        route = "voice",
        labelRes = R.string.nav_voice,
        selectedIcon = Icons.Filled.RecordVoiceOver,
        unselectedIcon = Icons.Outlined.RecordVoiceOver,
        testTag = "nav_voice"
    )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureAppCheck(this, intent)
        enableEdgeToEdge()
        setContent {
            val viewModel: SaraViewModel = viewModel()
            val prefs by viewModel.preferences.collectAsStateWithLifecycle()

            SaraTheme(darkTheme = prefs.darkTheme) {
                SaraAppScaffold(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun SaraAppScaffold(viewModel: SaraViewModel) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val destinations = listOf(
        SaraDestination.Sara,
        SaraDestination.Hub,
        SaraDestination.Personal,
        SaraDestination.Voice
    )

    val navigateTo: (SaraDestination) -> Unit = { dest ->
        navController.navigate(dest.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWideScreen = maxWidth >= 600.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing,
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (!isWideScreen) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
                    ) {
                        destinations.forEach { dest ->
                            val selected = currentDestination?.hierarchy?.any { it.route == dest.route } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTo(dest) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = stringResource(id = dest.labelRes)
                                    )
                                },
                                label = { Text(stringResource(id = dest.labelRes)) },
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isWideScreen) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
                    ) {
                        destinations.forEach { dest ->
                            val selected = currentDestination?.hierarchy?.any { it.route == dest.route } == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTo(dest) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = stringResource(id = dest.labelRes)
                                    )
                                },
                                label = { Text(stringResource(id = dest.labelRes)) },
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    NavHost(
                        navController = navController,
                        startDestination = SaraDestination.Sara.route
                    ) {
                        composable(SaraDestination.Sara.route) {
                            AssistantStageScreen(viewModel = viewModel, isWideScreen = isWideScreen)
                        }
                        composable(SaraDestination.Hub.route) {
                            PhoneHubScreen(viewModel = viewModel)
                        }
                        composable(SaraDestination.Personal.route) {
                            PersonalVaultScreen(viewModel = viewModel)
                        }
                        composable(SaraDestination.Voice.route) {
                            VoiceProfileScreen(viewModel = viewModel)
                        }
                    }
                }
            }
        }
    }
}
