package com.arv.app.feature.promptlibrary

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.arv.app.core.di.ServiceLocator
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.arv.app.core.model.PromptStatus

class PromptLibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = ServiceLocator.storyRepository(application)
    private val familyId = ServiceLocator.familyId

    init{
        viewModelScope.launch {
            repo.seedPromptsIfEmpty(
                familyId = familyId,
                now = System.currentTimeMillis()
            )
        }
    }
    val savedPrompts =
        repo.observeSavedPrompts(familyId)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                emptyList()
            )
    val prompts =
        repo.observePromptsFor(familyId, "Suggested")
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                emptyList()
            )

    val myQuestions =
        repo.observePromptsFor(familyId, "My Questions")
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                emptyList()
            )

    fun savePrompt(promptId: String) {
        viewModelScope.launch {
            repo.setPromptStatus(
                promptId = promptId,
                status = PromptStatus.SAVED,
                now = System.currentTimeMillis()
            )
        }
    }
    fun addUserPrompt(text: String) {
        viewModelScope.launch {
            repo.addUserPrompt(
                familyId = familyId,
                text = text,
                category = "My Questions",
                now = System.currentTimeMillis()
            )
        }
    }

    fun saveWhyThisOnePrompt(text: String) {
        viewModelScope.launch {
            repo.saveWhyThisOnePrompt(
                familyId = familyId,
                text = text,
                now = System.currentTimeMillis()
            )

        }
    }
    fun removeSavedPrompt(promptId: String){
        viewModelScope.launch {
            repo.setPromptStatus(
                promptId = promptId,
                status = PromptStatus.SUGGESTED,
                now = System.currentTimeMillis()
            )
        }
    }
}