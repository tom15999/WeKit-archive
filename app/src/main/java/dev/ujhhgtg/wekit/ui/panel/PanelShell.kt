package dev.ujhhgtg.wekit.ui.panel

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Close
import dev.ujhhgtg.wekit.features.items.chat.panel.PanelUiState
import dev.ujhhgtg.wekit.ui.utils.CommonContextWrapper
import dev.ujhhgtg.wekit.ui.utils.InjectedUiTheme
import dev.ujhhgtg.wekit.utils.android.isDarkMode

data class PanelRailItem<T>(
    val destination: T,
    val icon: ImageVector,
    val label: String,
)

data class PanelAction(
    val icon: ImageVector,
    val label: String,
    val enabled: Boolean = true,
    val showLabel: Boolean = false,
    val onLongClick: (() -> Unit)? = null,
    val onClick: () -> Unit,
)

internal data class PanelImportOption<T>(
    val mode: T,
    val title: String,
    val description: String,
    val icon: ImageVector,
)

class PanelDialogScope internal constructor(private val dialog: Dialog) {
    fun dismiss() = dialog.dismiss()
}

@Suppress("DEPRECATION")
fun showPanelDialog(
    context: Context,
    onDismiss: () -> Unit = {},
    content: @Composable PanelDialogScope.() -> Unit,
) {
    val wrapped = CommonContextWrapper(context)
    // ComponentDialog supplies both back-dispatcher owners required by the current
    // activity-compose BackHandler implementation. A plain Dialog has neither owner
    // in an injected WeChat window and crashes as soon as BackHandler enters composition.
    val dialog = ComponentDialog(wrapped, android.R.style.Theme_Translucent_NoTitleBar)
    val scope = PanelDialogScope(dialog)

    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    dialog.window?.apply {
        setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        clearFlags(
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                    WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION,
        )
        WindowCompat.setDecorFitsSystemWindows(this, false)
        statusBarColor = Color.TRANSPARENT
        navigationBarColor = Color.TRANSPARENT
        navigationBarDividerColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isStatusBarContrastEnforced = false
            isNavigationBarContrastEnforced = false
        }
        WindowInsetsControllerCompat(this, decorView).apply {
            isAppearanceLightStatusBars = !wrapped.isDarkMode
            isAppearanceLightNavigationBars = !wrapped.isDarkMode
        }
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setDimAmount(0.3f)
        setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN,
        )
        attributes = attributes.apply { gravity = Gravity.BOTTOM }
    }
    dialog.setCancelable(true)
    dialog.setContentView(
        ComposeView(wrapped).apply {
            setContent {
                CompositionLocalProvider(LocalContext provides wrapped) {
                    InjectedUiTheme {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .imePadding()
                                .clickable(
                                    indication = null,
                                    interactionSource = null,
                                    onClick = scope::dismiss,
                                ),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(0.65f)
                                    .clickable(
                                        indication = null,
                                        interactionSource = null,
                                        onClick = {},
                                    ),
                            ) {
                                scope.content()
                            }
                        }
                    }
                }
            }
        },
    )
    dialog.setOnDismissListener {
        onDismiss()
    }
    dialog.show()
    dialog.window?.setLayout(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
    )
}

@Composable
internal fun <T> PanelImportModePrompt(
    options: List<PanelImportOption<T>>,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
) {
    PanelFullOverlay(onDismiss) {
        Text("选择导入方式", style = MaterialTheme.typography.titleMedium)
        options.forEach { option ->
            ListItem(
                modifier = Modifier.clickable { onSelect(option.mode) },
                colors = panelListItemColors(),
                headlineContent = { Text(option.title) },
                supportingContent = { Text(option.description) },
                leadingContent = { Icon(option.icon, null) },
            )
        }
    }
}

@Composable
fun <T> PanelShell(
    railItems: List<PanelRailItem<T>>,
    selected: T,
    title: String,
    actions: List<PanelAction> = emptyList(),
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    onBack: () -> Unit = onDismiss,
    titleContent: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    BackHandler(onBack = onBack)
    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.84f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 3.dp,
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            LazyColumn(
                modifier = Modifier
                    .width(64.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items(railItems) { item ->
                    val isSelected = item.destination == selected
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                                else androidx.compose.ui.graphics.Color.Transparent,
                            )
                            .clickable { onSelect(item.destination) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            tint = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }

            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (titleContent == null) {
                        Text(
                            text = title,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        titleContent()
                        Box(Modifier.weight(1f))
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(MaterialSymbols.Outlined.Close, "关闭")
                    }
                }
                HorizontalDivider()
                if (actions.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        actions.forEach { action ->
                            if (action.showLabel) {
                                if (action.onLongClick == null) {
                                    TextButton(onClick = action.onClick, enabled = action.enabled) {
                                        Icon(action.icon, action.label, Modifier.size(20.dp))
                                        Text(action.label, Modifier.padding(start = 6.dp))
                                    }
                                } else {
                                    CompositionLocalProvider(
                                        LocalContentColor provides MaterialTheme.colorScheme.primary,
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .defaultMinSize(minWidth = 64.dp, minHeight = 40.dp)
                                                .clip(CircleShape)
                                                .combinedClickable(
                                                    enabled = action.enabled,
                                                    onClick = action.onClick,
                                                    onLongClick = action.onLongClick,
                                                )
                                                .padding(horizontal = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(action.icon, action.label, Modifier.size(20.dp))
                                            Text(
                                                text = action.label,
                                                modifier = Modifier.padding(start = 6.dp),
                                                style = MaterialTheme.typography.labelLarge,
                                            )
                                        }
                                    }
                                }
                            } else {
                                IconButton(onClick = action.onClick, enabled = action.enabled) {
                                    Icon(action.icon, action.label)
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                }
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }
}

@Composable
fun <T> PanelStateContent(
    state: PanelUiState<T>,
    onRetry: (() -> Unit)? = null,
    content: @Composable (T) -> Unit,
) {
    when (state) {
        PanelUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        is PanelUiState.Content -> content(state.value)
        is PanelUiState.Empty -> PanelMessage(state.message)
        is PanelUiState.Error -> PanelMessage(state.message, onRetry)
    }
}

@Composable
private fun PanelMessage(message: String, onRetry: (() -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onRetry != null) TextButton(onClick = onRetry) { Text("重试") }
    }
}

@Composable
fun PanelEmptyAction(
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun PanelTextPrompt(
    title: String,
    label: String,
    initialValue: String = "",
    confirmText: String = "确定",
    allowBlank: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    PanelOverlay(onDismiss = onDismiss) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("取消") }
            TextButton(onClick = { onConfirm(value.trim()) }, enabled = allowBlank || value.isNotBlank()) {
                Text(confirmText)
            }
        }
    }
}

@Composable
fun PanelNumberPrompt(
    title: String,
    label: String,
    initialValue: Long,
    minValue: Long,
    maxValue: Long = Long.MAX_VALUE,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue.toString()) }
    val parsed = value.toLongOrNull()
    val error = when {
        value.isBlank() || parsed == null -> "请输入有效的整数"
        parsed < minValue -> "不能小于 $minValue"
        parsed > maxValue -> "不能大于 $maxValue"
        else -> null
    }
    PanelOverlay(onDismiss = onDismiss) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text(label) },
            supportingText = error?.let { message -> { Text(message) } },
            isError = error != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("取消") }
            TextButton(onClick = { parsed?.let(onConfirm) }, enabled = error == null) { Text("确定") }
        }
    }
}

@Composable
fun PanelConfirmation(
    title: String,
    message: String,
    confirmText: String = "确定",
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    PanelOverlay(onDismiss = onDismiss) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("取消") }
            TextButton(onClick = onConfirm) { Text(confirmText) }
        }
    }
}

@Composable
fun PanelProgressOverlay(message: String, progress: Float? = null) {
    PanelOverlay(onDismiss = {}) {
        Text(message, style = MaterialTheme.typography.titleMedium)
        if (progress == null) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        } else {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
fun PanelFullOverlay(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.38f))
            .clickable(indication = null, interactionSource = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .clickable(indication = null, interactionSource = null, onClick = {}),
            shape = RoundedCornerShape(8.dp),
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                content = content,
            )
        }
    }
}

@Composable
fun PanelPageOverlay(
    onDismiss: () -> Unit,
    onBack: () -> Unit = onDismiss,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.38f))
            .clickable(indication = null, interactionSource = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
                .clickable(indication = null, interactionSource = null, onClick = {}),
            shape = RoundedCornerShape(8.dp),
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun PanelOverlay(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) = PanelFullOverlay(onDismiss, content)
