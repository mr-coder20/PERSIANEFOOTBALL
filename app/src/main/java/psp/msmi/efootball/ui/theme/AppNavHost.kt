package psp.msmi.efootball.ui.theme

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable

object NavRoutes {
    const val MAIN = "main"
    const val SETTINGS = "settings"
}

@Composable
fun AppNavHost(
    navController: NavHostController,
    // downloadProgress: Int, // <<-- حذف شد
    // isDownloading: Boolean, // <<-- حذف شد
    onDownloadRequest: () -> Unit, // این از MainActivity می آید و برای MainScreen است
    onStartGame: () -> Unit,
    onOpenSettingsPage: () -> Unit, // این از MainActivity می آید و برای ناوبری به Settings است
    onSettingsChosen: (String) -> Unit, // این از MainActivity می آید و پس از اعمال تنظیمات در SettingScreen فراخوانی می شود
    onOpenRubika: () -> Unit,
    onInstallSimulator: () -> Unit,
    isDarkMode: Boolean,
    onToggleDarkMode: () -> Unit,
    backgroundChoice: String
) {
    NavHost(navController = navController, startDestination = NavRoutes.MAIN) {
        composable(NavRoutes.MAIN) {
            MainScreen(
                // downloadProgress = downloadProgress, // <<-- حذف شد
                // isDownloading = isDownloading, // <<-- حذف شد
                onDownloadRequest = onDownloadRequest,
                onStartGame = onStartGame,
                onOpenSettings = onOpenSettingsPage, // اطمینان از اینکه onOpenSettings در MainScreen به این متصل است
                onOpenRubika = onOpenRubika,
                onInstallSimulator = onInstallSimulator,
                isDarkMode = isDarkMode,
                onToggleDarkMode = onToggleDarkMode,
                backgroundChoice = backgroundChoice
            )
        }

        composable(NavRoutes.SETTINGS) {
            SettingScreen(
                // این settingId از SettingScreen می آید
                onSettingApplied = { settingId ->
                    // اکشن onSettingsChosen از MainActivity را با id تنظیم اعمال شده فراخوانی می کنیم
                    onSettingsChosen(settingId)

                    // تصمیم برای بازگشت به صفحه اصلی:
                    // اگر می خواهید بلافاصله پس از اعمال تنظیمات به صفحه اصلی بازگردید،
                    // این خط را از کامنت خارج کنید.
                    // navController.popBackStack()
                    // یا اینکه این کار را در onSettingsChosen در MainActivity انجام دهید.
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
