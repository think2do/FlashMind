package com.lgjn.inspirationcapsule.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.lgjn.inspirationcapsule.api.DifyApiService
import com.lgjn.inspirationcapsule.data.Inspiration
import com.lgjn.inspirationcapsule.data.InspirationRepository
import kotlinx.coroutines.launch
import java.io.File

sealed class ProcessingState {
    object Idle : ProcessingState()
    /** 语音转写中（Step 1） */
    object Transcribing : ProcessingState()
    /** AI 生成文案中（Step 2 / 文案路径） */
    object Processing : ProcessingState()
    data class Success(val text: String) : ProcessingState()
    data class Error(val message: String) : ProcessingState()
}

class InspirationViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = InspirationRepository(application)

    private val _inspirations = MutableLiveData<List<Inspiration>>(emptyList())
    val inspirations: LiveData<List<Inspiration>> = _inspirations

    private val _processingState = MutableLiveData<ProcessingState>(ProcessingState.Idle)
    val processingState: LiveData<ProcessingState> = _processingState

    init {
        loadInspirations()
    }

    fun loadInspirations() {
        viewModelScope.launch {
            _inspirations.value = repository.getAll()
        }
    }

    /**
     * 处理语音录音（两步）：
     *   Step 1: audio-to-text 转写 → Transcribing 状态
     *   Step 2: chat-messages 提炼 → Processing 状态
     *   完成后 → Success 状态
     */
    fun processAudioFile(audioFile: File) {
        viewModelScope.launch {
            // Step 1 提示
            _processingState.value = ProcessingState.Transcribing

            val result = DifyApiService.processAudio(audioFile)

            result.onSuccess { text ->
                val inspiration = Inspiration(
                    content = text,
                    // audioPath 不再保存：文件转写后已删除，App 内无回放功能
                    createdAt = System.currentTimeMillis()
                )
                repository.insert(inspiration)
                _processingState.value = ProcessingState.Success(text)
                loadInspirations()
            }
            result.onFailure { error ->
                _processingState.value = ProcessingState.Error(
                    error.message ?: "AI处理失败，请检查网络后重试"
                )
            }
        }
    }

    /**
     * 处理文案输入（一步）：
     *   chat-messages 提炼 → Processing 状态 → Success
     */
    fun processTextInput(text: String) {
        viewModelScope.launch {
            _processingState.value = ProcessingState.Processing
            val result = DifyApiService.processText(text)
            result.onSuccess { aiText ->
                val inspiration = Inspiration(
                    content = aiText,
                    createdAt = System.currentTimeMillis()
                )
                repository.insert(inspiration)
                _processingState.value = ProcessingState.Success(aiText)
                loadInspirations()
            }
            result.onFailure { error ->
                _processingState.value = ProcessingState.Error(
                    error.message ?: "AI处理失败，请检查网络后重试"
                )
            }
        }
    }

    /** 直接保存灵感（不经过AI，用于手动输入直接保存） */
    fun addManualInspiration(content: String) {
        viewModelScope.launch {
            repository.insert(Inspiration(content = content))
            loadInspirations()
        }
    }

    fun updateContent(id: Long, content: String) {
        viewModelScope.launch {
            repository.updateContent(id, content)
            loadInspirations()
        }
    }

    fun deleteInspiration(id: Long) {
        viewModelScope.launch {
            repository.delete(id)
            loadInspirations()
        }
    }

    fun resetProcessingState() {
        _processingState.value = ProcessingState.Idle
    }
}
