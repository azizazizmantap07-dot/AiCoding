package com.kaneki.aicoder.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kaneki.aicoder.data.local.SecurePrefs
import com.kaneki.aicoder.data.local.ZipFileStore
import com.kaneki.aicoder.data.local.db.AppDatabase
import com.kaneki.aicoder.data.repository.ChatRepository
import com.kaneki.aicoder.data.repository.ProjectRepository
import com.kaneki.aicoder.domain.model.FileTree
import com.kaneki.aicoder.domain.zip.SingleFileImporter
import com.kaneki.aicoder.domain.zip.ZipExtractor
import com.kaneki.aicoder.ui.chat.ChatScreen
import com.kaneki.aicoder.ui.chat.ChatViewModel
import com.kaneki.aicoder.ui.export.ExportScreen
import com.kaneki.aicoder.ui.settings.ApiKeySetupScreen
import com.kaneki.aicoder.ui.settings.SettingsScreen
import com.kaneki.aicoder.ui.upload.UploadScreen
import kotlinx.coroutines.launch

object Routes {
    const val API_KEY = "api_key"
    const val UPLOAD = "upload"
    const val SETTINGS = "settings"
    const val FREE_CHAT = "free_chat"
    const val PROJECT_CHAT = "project_chat/{projectId}"
    const val EXPORT = "export/{projectId}"

    fun projectChat(projectId: String) = "project_chat/$projectId"
    fun export(projectId: String) = "export/$projectId"
}

/** Session in-memory untuk FileTree aktif (terlalu besar untuk nav args). */
class ProjectSession {
    var projectId: String? = null
        private set
    var fileTree: FileTree = FileTree.EMPTY
        private set

    fun set(projectId: String, fileTree: FileTree) {
        this.projectId = projectId
        this.fileTree = fileTree
    }

    fun clear() {
        this.projectId = null
        this.fileTree = FileTree.EMPTY
    }
}

@Composable
fun AppNavHost() {
    val context = LocalContext.current
    val navController = rememberNavController()
    val session = remember { ProjectSession() }
    val scope = rememberCoroutineScope()
    val app = context.applicationContext as android.app.Application
    val projectRepository = remember {
        ProjectRepository(
            contentResolver = app.contentResolver,
            zipExtractor = ZipExtractor(app.contentResolver),
            singleFileImporter = SingleFileImporter(app.contentResolver),
            zipFileStore = ZipFileStore(app),
            projectDao = AppDatabase.getInstance(app).projectDao()
        )
    }
    val chatRepository = remember {
        ChatRepository(AppDatabase.getInstance(app).chatDao())
    }
    // True jika user membuka layar Import dari chat bebas (tanpa project).
    // Setelah import sukses, riwayat free-chat dipindah ke project baru agar sesi tidak hilang.
    var importFromFreeChat by remember { mutableStateOf(false) }
    val start = if (SecurePrefs(context).hasAnyApiKey()) Routes.FREE_CHAT else Routes.API_KEY

    NavHost(navController = navController, startDestination = start) {
        composable(Routes.API_KEY) {
            ApiKeySetupScreen(
                onKeySaved = {
                    navController.navigate(Routes.FREE_CHAT) {
                        popUpTo(Routes.API_KEY) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.FREE_CHAT) {
            LaunchedEffect(Unit) { session.clear() }
            ChatScreen(
                projectId = null,
                fileTree = FileTree.EMPTY,
                onBack = { /* sudah di root, tidak ada tempat lain untuk kembali */ },
                onOpenExport = { /* tidak ada project untuk diekspor */ },
                onImportProject = {
                    importFromFreeChat = true
                    navController.navigate(Routes.UPLOAD)
                },
                onNewProject = {
                    navController.navigate(Routes.FREE_CHAT) {
                        popUpTo(Routes.FREE_CHAT) { inclusive = true }
                    }
                },
                onOpenProject = { otherProjectId ->
                    scope.launch {
                        val imported = projectRepository.openExisting(otherProjectId)
                        if (imported != null) {
                            session.set(imported.projectId, imported.fileTree)
                            navController.navigate(Routes.projectChat(otherProjectId))
                        }
                    }
                },
                onOpenSettings = {
                    navController.navigate(Routes.SETTINGS)
                }
            )
        }

        composable(Routes.UPLOAD) {
            UploadScreen(
                onOpenProjectChat = { projectId, fileTree ->
                    scope.launch {
                        // Jika datang dari chat bebas: pindahkan riwayat chat ke project baru
                        // supaya percakapan sebelumnya tetap ada di sesi yang sama.
                        if (importFromFreeChat) {
                            chatRepository.migrateProject(
                                ChatViewModel.FREE_CHAT_ID,
                                projectId
                            )
                        }
                        session.set(projectId, fileTree)
                        navController.navigate(Routes.projectChat(projectId)) {
                            // Dari free chat: tutup free + upload, langsung ke project chat.
                            // Dari project lain: cukup tutup upload.
                            if (importFromFreeChat) {
                                popUpTo(Routes.FREE_CHAT) { inclusive = true }
                            } else {
                                popUpTo(Routes.UPLOAD) { inclusive = true }
                            }
                        }
                        importFromFreeChat = false
                    }
                },
                onOpenSettings = {
                    navController.navigate(Routes.SETTINGS)
                },
                onBack = {
                    importFromFreeChat = false
                    navController.popBackStack()
                }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = {
                    if (SecurePrefs(context).hasAnyApiKey()) {
                        navController.popBackStack()
                    } else {
                        navController.navigate(Routes.API_KEY) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
                onChangeApiKey = {
                    navController.navigate(Routes.API_KEY)
                }
            )
        }

        composable(
            route = Routes.PROJECT_CHAT,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType })
        ) { entry ->
            val projectId = entry.arguments?.getString("projectId") ?: return@composable
            val tree = if (session.projectId == projectId) session.fileTree else FileTree.EMPTY
            ChatScreen(
                projectId = projectId,
                fileTree = tree,
                onBack = {
                    navController.navigate(Routes.FREE_CHAT) {
                        popUpTo(Routes.FREE_CHAT) { inclusive = true }
                    }
                },
                onOpenExport = {
                    navController.navigate(Routes.export(projectId))
                },
                onImportProject = {
                    importFromFreeChat = false
                    navController.navigate(Routes.UPLOAD)
                },
                onNewProject = {
                    navController.navigate(Routes.FREE_CHAT) {
                        popUpTo(Routes.FREE_CHAT) { inclusive = true }
                    }
                },
                onOpenProject = { otherProjectId ->
                    // Muat FileTree project lain dari disk sebelum pindah, supaya
                    // ChatScreen tujuan tidak menerima FileTree.EMPTY.
                    scope.launch {
                        val imported = projectRepository.openExisting(otherProjectId)
                        if (imported != null) {
                            session.set(imported.projectId, imported.fileTree)
                            navController.navigate(Routes.projectChat(otherProjectId)) {
                                popUpTo(Routes.PROJECT_CHAT) { inclusive = true }
                            }
                        }
                    }
                },
                onOpenSettings = {
                    navController.navigate(Routes.SETTINGS)
                }
            )
        }

        composable(
            route = Routes.EXPORT,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType })
        ) { entry ->
            val projectId = entry.arguments?.getString("projectId") ?: return@composable
            ExportScreen(
                projectId = projectId,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
