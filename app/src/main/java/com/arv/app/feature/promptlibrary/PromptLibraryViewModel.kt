package com.arv.app.feature.promptlibrary

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.arv.app.core.di.ServiceLocator
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn


class PromptLibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = ServiceLocator.storyRepository(application)
    private val familyId = ServiceLocator.familyId
    val savedPrompts =
        repo.observeSavedPrompts(familyId)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                emptyList()
            )
}