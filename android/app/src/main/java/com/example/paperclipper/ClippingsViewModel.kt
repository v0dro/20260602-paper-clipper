package com.example.paperclipper

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.paperclipper.auth.AuthManager
import com.example.paperclipper.data.AppDatabase
import com.example.paperclipper.data.Clipping
import com.example.paperclipper.data.ClippingsRepository
import com.example.paperclipper.data.CommentEntity
import com.example.paperclipper.data.TagEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ClippingsViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = ClippingsRepository(app, AppDatabase.get(app))
    private val authManager = AuthManager(app)

    /** Email of the signed-in user, or null when logged out / not configured. */
    val authEmail: StateFlow<String?> = authManager.email

    val clippings: StateFlow<List<Clipping>> =
        repository.clippings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** All global tags, usable from any clipping. */
    val tags: StateFlow<List<TagEntity>> =
        repository.allTags.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun tagsFor(fileName: String): Flow<List<TagEntity>> = repository.tagsFor(fileName)

    fun commentsFor(fileName: String): Flow<List<CommentEntity>> = repository.commentsFor(fileName)

    /** Reconcile DB with disk and analyze any pending clippings. Call when the library is shown. */
    fun refresh() {
        viewModelScope.launch { repository.reconcileAndProcess() }
    }

    fun delete(files: List<File>) {
        viewModelScope.launch { repository.delete(files) }
    }

    fun retry(fileName: String) {
        viewModelScope.launch { repository.retry(fileName) }
    }

    fun createTag(fileName: String, name: String) {
        viewModelScope.launch { repository.createAndAssignTag(fileName, name) }
    }

    fun setTag(fileName: String, tagId: Long, assigned: Boolean) {
        viewModelScope.launch { repository.setTagAssigned(fileName, tagId, assigned) }
    }

    fun addComment(fileName: String, text: String) {
        viewModelScope.launch { repository.addComment(fileName, text) }
    }

    fun deleteComment(id: Long) {
        viewModelScope.launch { repository.deleteComment(id) }
    }

    /** Writes the export ZIP to [uri]; [onResult] is invoked on completion with success/failure. */
    fun export(uri: Uri, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    resolver.openOutputStream(uri)?.use { repository.exportTo(it) }
                        ?: error("Could not open output stream")
                }
            }.isSuccess
            onResult(ok)
        }
    }

    // --- Auth (scaffold; inert until Firebase is configured) ---
    fun signInIntent(): Intent? = authManager.signInIntent()
    fun handleSignInResult(data: Intent?) = authManager.handleSignInResult(data)
    fun signOut() = authManager.signOut()
}
