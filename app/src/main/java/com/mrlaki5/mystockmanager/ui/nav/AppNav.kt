package com.mrlaki5.mystockmanager.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mrlaki5.mystockmanager.ui.events.EventDetailScreen
import com.mrlaki5.mystockmanager.ui.events.EventListScreen
import com.mrlaki5.mystockmanager.ui.images.ImageDetailScreen
import com.mrlaki5.mystockmanager.ui.settings.SettingsScreen

private object Routes {
    const val EVENTS = "events"
    const val SETTINGS = "settings"
    const val EVENT_DETAIL = "event/{eventId}"
    fun eventDetail(id: Long) = "event/$id"
    const val IMAGE_DETAIL = "image/{imageId}"
    fun imageDetail(id: Long) = "image/$id"
}

@Composable
fun AppNav() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.EVENTS) {
        composable(Routes.EVENTS) {
            EventListScreen(
                onOpenEvent = { navController.navigate(Routes.eventDetail(it)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            route = Routes.EVENT_DETAIL,
            arguments = listOf(navArgument("eventId") { type = NavType.LongType }),
        ) {
            EventDetailScreen(
                onBack = { navController.popBackStack() },
                onOpenImage = { navController.navigate(Routes.imageDetail(it)) },
            )
        }
        composable(
            route = Routes.IMAGE_DETAIL,
            arguments = listOf(navArgument("imageId") { type = NavType.LongType }),
        ) {
            ImageDetailScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
