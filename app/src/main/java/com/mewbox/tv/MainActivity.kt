package com.mewbox.tv

import android.net.Uri
import android.os.Bundle
import timber.log.Timber
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mewbox.tv.ui.detail.DetailScreen
import com.mewbox.tv.ui.history.HistoryScreen
import com.mewbox.tv.ui.home.HomeScreen
import com.mewbox.tv.ui.search.SearchScreen
import com.mewbox.tv.ui.setting.SettingsScreen
import com.mewbox.tv.ui.theme.MewBoxTheme
import dagger.hilt.android.AndroidEntryPoint

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "首页", Icons.Filled.Home),
    Search("search", "搜索", Icons.Filled.Search),
    History("history", "历史", Icons.Filled.History),
    Settings("settings", "设置", Icons.Filled.Settings),
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MewBoxTheme { MainScreen() } }
    }
}

@Composable
private fun MainScreen() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val isBottomBarVisible = currentRoute == null || Tab.entries.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (isBottomBarVisible) {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = backStack?.destination?.hierarchy?.any { it.route == tab.route } == true,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Tab.Home.route,
            modifier = Modifier.padding(padding),
        ) {
            val navigateToDetail: (String, String, String) -> Unit = { siteKey, vodId, vodName ->
                Timber.i("导航至详情: siteKey=$siteKey, vodId=$vodId, vodName=$vodName")
                nav.navigate("detail/$siteKey?vodId=${Uri.encode(vodId)}&vodName=${Uri.encode(vodName)}")
            }

            composable(Tab.Home.route) {
                HomeScreen(onNavigateToDetail = navigateToDetail)
            }
            composable(Tab.Search.route) {
                SearchScreen(onNavigateToDetail = navigateToDetail)
            }
            composable(Tab.History.route) {
                HistoryScreen(onNavigateToDetail = navigateToDetail)
            }
            composable(Tab.Settings.route) {
                SettingsScreen()
            }
            composable(
                route = "detail/{siteKey}?vodId={vodId}&vodName={vodName}",
                arguments = listOf(
                    navArgument("siteKey") { type = NavType.StringType },
                    navArgument("vodId") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("vodName") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) {
                DetailScreen(
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }
}

@Composable
private fun Placeholder(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text) }
}
