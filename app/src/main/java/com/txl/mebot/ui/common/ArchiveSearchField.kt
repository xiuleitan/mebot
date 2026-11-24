package com.txl.mebot.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txl.mebot.R
import com.txl.mebot.ui.schedule.scheduleColors

/** 首页与档案页共用44dp搜索框，字号变化也不额外增加上下内边距。 */
@Composable
internal fun ArchiveSearchField(query: String, changed: (String) -> Unit, search: () -> Unit,
    modifier: Modifier, fieldModifier: Modifier = Modifier,
    searchLabel: String = stringResource(R.string.archive_search_label),
    placeholder: String = stringResource(R.string.archive_search_placeholder),
    searchAction: String = "搜索档案", clearAction: String = stringResource(R.string.archive_search_clear),
    actions: @Composable RowScope.() -> Unit = {}) {
    val colors = scheduleColors()
    val performSearch = hapticAction(search)
    Surface(modifier.height(44.dp), shape = RoundedCornerShape(18.dp), color = colors.surface,
        border = BorderStroke(1.dp, colors.divider.copy(alpha = .7f))) {
        Row(Modifier.fillMaxSize().padding(start = 12.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(performSearch, enabled = query.isNotBlank(), modifier = Modifier.size(38.dp)) {
                Icon(painterResource(R.drawable.ic_archive_search), searchAction, Modifier.size(19.dp), tint = colors.secondary)
            }
            BasicTextField(query, changed, modifier = fieldModifier.weight(1f).fillMaxHeight().semantics { contentDescription = searchLabel },
                singleLine = true, textStyle = LocalTextStyle.current.copy(color = colors.ink, fontSize = 14.sp, lineHeight = 20.sp),
                cursorBrush = SolidColor(colors.accent), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) performSearch() }),
                decorationBox = { field -> Box(Modifier.fillMaxSize().padding(horizontal = 9.dp), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text(placeholder, color = colors.secondary, fontSize = 14.sp, lineHeight = 20.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    field()
                } })
            actions()
            if (query.isNotEmpty()) IconButton(hapticAction { changed("") }, modifier = Modifier.size(38.dp)) {
                Icon(painterResource(R.drawable.ic_close), clearAction, Modifier.size(18.dp))
            }
        }
    }
}
