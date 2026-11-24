package com.txl.mebot.ui.home

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.txl.mebot.MebotApplication
import com.txl.mebot.data.local.HomeRoutingSettingsEntity
import com.txl.mebot.ui.archive.archiveActivity
import com.txl.mebot.ui.schedule.scheduleColors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class HomeRoutingConfigState(val endpoint: String = "", val revision: Long = 0, val loaded: Boolean = false,
    val busy: Boolean = false, val message: String? = null)
class HomeRoutingSettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = (app as MebotApplication).container.homeRoutingSettings
    private val mutable = MutableStateFlow(HomeRoutingConfigState())
    val state = mutable.asStateFlow()
    init { reload() }
    fun reload() { viewModelScope.launch {
        try { val saved = settings.read(); mutable.value = HomeRoutingConfigState(saved.endpoint, saved.revision, loaded = true) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.update { it.copy(message = "路由配置暂未读取成功，点击重试") } }
    } }
    fun change(text: String) { if (!state.value.busy) mutable.update { it.copy(endpoint = text, message = null) } }
    fun save() {
        val before = state.value
        if (!before.loaded || before.busy) return
        mutable.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                val saved = settings.save(before.endpoint, before.revision)
                mutable.value = HomeRoutingConfigState(saved.endpoint, saved.revision, loaded = true, message = "路由地址已保存")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.update { it.copy(message = failure.message ?: "路由配置未能保存，请重试") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
}

@Composable
internal fun HomeRoutingSettingsEntry() {
    val context = LocalContext.current
    if (context.applicationContext !is MebotApplication) return
    val owner = context.archiveActivity()
    val model = remember(owner) { ViewModelProvider(owner)[HomeRoutingSettingsViewModel::class.java] }
    val state by model.state.collectAsStateWithLifecycle()
    val colors = scheduleColors()
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = colors.surface) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("首页决策模型", color = colors.ink, fontSize = 18.sp)
            Text("decision-model-preview", color = colors.secondary, fontSize = 14.sp)
            Text("只判断本次输入文字的目标 Bot，沿用已保存的百炼 API Key。", color = colors.secondary, fontSize = 12.sp)
            OutlinedTextField(state.endpoint, model::change, Modifier.fillMaxWidth(), enabled = state.loaded && !state.busy,
                label = { Text("System One 接口地址") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
            state.message?.let { Text(it, color = colors.secondary, fontSize = 12.sp) }
            Button(if (state.loaded) model::save else model::reload, enabled = !state.busy) {
                Text(if (state.loaded) "保存路由地址" else "重新读取")
            }
        }
    }
}
