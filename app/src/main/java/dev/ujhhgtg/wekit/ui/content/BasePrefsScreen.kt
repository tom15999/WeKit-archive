package dev.ujhhgtg.wekit.ui.content

import android.content.Context
import android.text.InputType
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Arrow_back
import com.composables.icons.materialsymbols.outlined.Keyboard_arrow_right
import com.composables.icons.materialsymbols.outlined.Settings
import dev.ujhhgtg.wekit.activity.StandardActivity
import dev.ujhhgtg.wekit.constants.Preferences
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.utils.AppTheme
import dev.ujhhgtg.wekit.ui.utils.CommonContextWrapper
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger

// ---------------------------------------------------------------------------
//  Internal state models
// ---------------------------------------------------------------------------

private sealed class PrefRow {
    abstract val rowKey: String

    data class Category(override val rowKey: String, val title: String) : PrefRow()

    data class Switch(
        override val rowKey: String,
        val configKey: String,
        val title: String,
        val summary: String,
        val icon: ImageVector?,
    ) : PrefRow()

    data class EditText(
        override val rowKey: String,
        val configKey: String,
        val title: String,
        val baseSummary: String,
        val defaultValue: String,
        val hint: String?,
        val inputType: Int,
        val maxLength: Int,
        val singleLine: Boolean,
        val icon: ImageVector?,
        val summaryFormatter: ((String) -> String)?,
    ) : PrefRow()

    data class Select(
        override val rowKey: String,
        val configKey: String,
        val title: String,
        val baseSummary: String,
        val options: Map<Int, String>,
        val defaultValue: Int,
        val icon: ImageVector?,
    ) : PrefRow()

    data class Plain(
        override val rowKey: String,
        val title: String,
        val summary: String?,
        val icon: ImageVector?,
        val onClick: ((Context) -> Unit)?,
    ) : PrefRow()

    data class HookSwitch(
        override val rowKey: String,
        val configKey: String,
        val title: String,
        val summary: String,
        val initialChecked: Boolean,
        val onBeforeToggle: (Boolean) -> Boolean,
        val bindCompletionCallback: ((Boolean) -> Unit) -> Unit,
    ) : PrefRow()

    data class HookClickable(
        override val rowKey: String,
        val configKey: String,
        val title: String,
        val summary: String,
        val showSwitch: Boolean,
        val initialChecked: Boolean,
        val onBeforeToggle: (Boolean) -> Boolean,
        val bindCompletionCallback: ((Boolean) -> Unit) -> Unit,
        val onClick: (Context) -> Unit,
    ) : PrefRow()
}

private data class DepInfo(
    val dependentRowKey: String,
    val enableWhen: Boolean,
    val hideWhenDisabled: Boolean,
)

private data class PreparedPreferences(
    val rows: List<PrefRow>,
    val dependencies: Map<String, List<DepInfo>>
)

// ---------------------------------------------------------------------------
//  Abstract Container Agnostic Configuration Base Class
// ---------------------------------------------------------------------------

abstract class BasePrefsScreen(private val title: String) {

    private val rows = mutableListOf<PrefRow>()
    private val dependencies = mutableMapOf<String, MutableList<DepInfo>>()
    private var rowCounter = 0

    abstract fun initPreferences()

    /**
    // Compiles preference items into immutable snapshots safely during composition.
     */
    @Composable
    private fun rememberPreparedPreferences(): PreparedPreferences {
        return remember(this) {
            rows.clear()
            dependencies.clear()
            rowCounter = 0
            initPreferences()
            PreparedPreferences(ArrayList(rows), LinkedHashMap(dependencies))
        }
    }

    // -----------------------------------------------------------------------
    //  Public API Targets
    // -----------------------------------------------------------------------

    /**
     * Renders preferences inside a floating card layout, matching the look
     * of the original standalone dialog view tree wrapper.
     */
    @Composable
    fun DialogContent(onDismiss: () -> Unit) {
        val (compiledRows, compiledDeps) = rememberPreparedPreferences()

        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 16.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Arrow_back,
                            contentDescription = "Back",
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium,
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(top = 8.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                PreferenceCoreList(
                    rows = compiledRows,
                    dependencies = compiledDeps,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    /**
     * Renders preferences as a full screen native list utilizing typical
     * Scaffold layouts. Perfect for standard target Activity setContent targets.
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ActivityContent(activity: ComponentActivity, onBack: () -> Unit) {
        val (compiledRows, compiledDeps) = rememberPreparedPreferences()

        CompositionLocalProvider(LocalContext provides activity,
            LocalActivity provides activity
        ) {
            AppTheme {
                BackHandler(onBack = onBack)
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(text = title, fontWeight = FontWeight.Medium)
                            },
                            navigationIcon = {
                                IconButton(onBack) {
                                    Icon(
                                        imageVector = MaterialSymbols.Outlined.Arrow_back,
                                        contentDescription = "Back"
                                    )
                                }
                            }
                        )
                    }
                ) { paddingValues ->
                    PreferenceCoreList(
                        rows = compiledRows,
                        dependencies = compiledDeps,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                    )
                }
            }
        }
    }

//    private var getStringUnhook: XC_MethodHook.Unhook? = null

    fun show(context: Context) {
        if (!Preferences.useActivityInsteadOfDialog) {
            showComposeDialog(context) {
                DialogContent(onDismiss)
            }
        }
        else {
            StandardActivity.launch(CommonContextWrapper.create(context),
//                onResume = {
                    // fix up Jetpack Compose
                    // come on Google why tf do you choose to use Resources here??
//                    getStringUnhook = Resources::class.asResolver().firstMethod { name = "getString"; parameters(int) }.hookBeforeDirectly {
                        // FIXME: no this is not a FIXME this is FIXYOURFUCKINGAISLOPGOOGLE
                        // if I set this to "", it sometimes triggers 'shortLabel cannot be empty'.
                        // if I set this to a non-empty string, it triggers a cryptic 'android.view.InputEventCompatProcessor.processInputEventForCompatibility(android.view.InputEvent)' NPE.
                        // see <project root>/fuckyougoogle
                        // see WeLauncher line 44
//                        result = ""
//                    }
//                },
//                onPause = {
//                    if (!this.isFinishing)
//                        getStringUnhook?.unhook()
//                },
//                onDestroy = {
//                    CoroutineScope(Dispatchers.Main).launch {
//                        delay(100.milliseconds)
//                        getStringUnhook?.unhook()
//                    }
//                }
            ) {
                setContent {
                    ActivityContent(this) { finish() }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    //  DSL Builder Pattern Implementations
    // -----------------------------------------------------------------------

    protected fun addCategory(title: String) {
        rows += PrefRow.Category(rowKey = nextKey("cat"), title = title)
    }

    protected fun addSwitchPreference(
        key: String,
        title: String,
        summary: String,
        icon: ImageVector?,
    ): String {
        val rk = nextKey("sw_$key")
        rows += PrefRow.Switch(rk, key, title, summary, icon)
        return rk
    }

    protected fun addSelectPreference(
        key: String,
        title: String,
        summary: String,
        options: Map<Int, String>,
        defaultValue: Int,
        icon: ImageVector?,
    ) {
        val rk = nextKey("sel_$key")
        rows += PrefRow.Select(rk, key, title, summary, options, defaultValue, icon)
    }

    protected fun addPreference(
        title: String,
        summary: String? = null,
        icon: ImageVector? = null,
        onClick: ((Context) -> Unit)? = null,
    ) {
        val rk = nextKey("pref_$title")
        rows += PrefRow.Plain(rk, title, summary, icon, onClick)
    }

    protected fun addHookSwitch(
        key: String,
        title: String,
        summary: String,
        initialChecked: Boolean,
        onBeforeToggle: (Boolean) -> Boolean,
        bindCompletionCallback: ((Boolean) -> Unit) -> Unit,
    ) {
        val rk = nextKey("hsw_$key")
        rows += PrefRow.HookSwitch(rk, key, title, summary, initialChecked, onBeforeToggle, bindCompletionCallback)
    }

    protected fun addHookClickable(
        key: String,
        title: String,
        summary: String,
        showSwitch: Boolean,
        initialChecked: Boolean,
        onBeforeToggle: (Boolean) -> Boolean,
        bindCompletionCallback: ((Boolean) -> Unit) -> Unit,
        onClick: (Context) -> Unit
    ) {
        val rk = nextKey("hcl_$key")
        rows += PrefRow.HookClickable(rk, key, title, summary, showSwitch, initialChecked, onBeforeToggle, bindCompletionCallback, onClick)
    }

    private fun nextKey(base: String) = "${base}_${rowCounter++}"
}

// ---------------------------------------------------------------------------
//  Unified State Engine & Shared Interface Core List
// ---------------------------------------------------------------------------

private const val TAG = "BasePrefUI"

@Composable
private fun PreferenceCoreList(
    rows: List<PrefRow>,
    dependencies: Map<String, List<DepInfo>>,
    modifier: Modifier = Modifier
) {
    val switchStates = remember(rows) {
        mutableStateMapOf<String, Boolean>().also { map ->
            rows.filterIsInstance<PrefRow.Switch>().forEach { map[it.configKey] = WePrefs.getBoolOrFalse(it.configKey) }
            rows.filterIsInstance<PrefRow.HookSwitch>().forEach { map[it.configKey] = it.initialChecked }
            rows.filterIsInstance<PrefRow.HookClickable>().forEach { map[it.configKey] = it.initialChecked }
        }
    }

    val summaryStates = remember(rows) {
        mutableStateMapOf<String, String>().also { map ->
            rows.filterIsInstance<PrefRow.EditText>().forEach { row ->
                val v = WePrefs.getStringOrDef(row.configKey, row.defaultValue)
                map[row.configKey] = row.summaryFormatter?.invoke(v) ?: if (v.isEmpty()) row.baseSummary else "${row.baseSummary}: $v"
            }
            rows.filterIsInstance<PrefRow.Select>().forEach { row ->
                val v = WePrefs.getIntOrDef(row.configKey, row.defaultValue)
                map[row.configKey] = row.options[v] ?: "${row.baseSummary}: $v"
            }
        }
    }

    fun rowEnabled(rowKey: String): Boolean {
        dependencies.forEach { (configKey, depList) ->
            val value = switchStates[configKey] ?: WePrefs.getBoolOrFalse(configKey)
            depList.forEach { dep ->
                if (dep.dependentRowKey == rowKey) return if (dep.enableWhen) value else !value
            }
        }
        return true
    }

    fun rowVisible(rowKey: String): Boolean {
        dependencies.forEach { (configKey, depList) ->
            val value = switchStates[configKey] ?: WePrefs.getBoolOrFalse(configKey)
            depList.forEach { dep ->
                if (dep.dependentRowKey == rowKey && dep.hideWhenDisabled) return if (dep.enableWhen) value else !value
            }
        }
        return true
    }

    var inputDialogRow by remember { mutableStateOf<PrefRow.EditText?>(null) }
    var selectDialogRow by remember { mutableStateOf<PrefRow.Select?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
    ) {
        rows.forEach { row ->
            when (row) {
                is PrefRow.Category -> {
                    Text(
                        text = row.title,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
                    )
                }
                is PrefRow.Switch -> {
                    AnimatedVisibility(visible = rowVisible(row.rowKey)) {
                        SwitchRow(
                            row = row,
                            checked = switchStates[row.configKey] ?: false,
                            enabled = rowEnabled(row.rowKey),
                            onCheckedChange = { checked ->
                                switchStates[row.configKey] = checked
                                WePrefs.putBool(row.configKey, checked)
                                WeLogger.d(TAG, "config changed [${row.configKey}] -> $checked")
                            },
                        )
                    }
                }
                is PrefRow.HookSwitch -> {
                    val checked = switchStates[row.configKey] ?: row.initialChecked
                    DisposableEffect(row.rowKey) {
                        row.bindCompletionCallback { switchStates[row.configKey] = it }
                        onDispose {}
                    }
                    HookSwitchRow(
                        title = row.title,
                        summary = row.summary,
                        checked = checked,
                        onCheckedChange = { requested ->
                            if (row.onBeforeToggle(requested)) switchStates[row.configKey] = requested
                        },
                    )
                }
                is PrefRow.HookClickable -> {
                    val checked = switchStates[row.configKey] ?: row.initialChecked
                    DisposableEffect(row.rowKey) {
                        row.bindCompletionCallback { switchStates[row.configKey] = it }
                        onDispose {}
                    }
                    HookClickableRow(
                        title = row.title,
                        summary = row.summary,
                        showSwitch = row.showSwitch,
                        checked = checked,
                        onCheckedChange = { requested ->
                            if (row.onBeforeToggle(requested)) switchStates[row.configKey] = requested
                        },
                        onClick = row.onClick,
                    )
                }
                is PrefRow.EditText -> {
                    AnimatedVisibility(visible = rowVisible(row.rowKey)) {
                        SimpleRow(
                            title = row.title,
                            summary = summaryStates[row.configKey] ?: row.baseSummary,
                            icon = row.icon,
                            enabled = rowEnabled(row.rowKey),
                            showArrow = true,
                            onClick = { inputDialogRow = row },
                        )
                    }
                }
                is PrefRow.Select -> {
                    AnimatedVisibility(visible = rowVisible(row.rowKey)) {
                        SimpleRow(
                            title = row.title,
                            summary = summaryStates[row.configKey] ?: row.baseSummary,
                            icon = row.icon,
                            enabled = rowEnabled(row.rowKey),
                            showArrow = true,
                            onClick = { selectDialogRow = row },
                        )
                    }
                }
                is PrefRow.Plain -> {
                    AnimatedVisibility(visible = rowVisible(row.rowKey)) {
                        SimpleRow(
                            title = row.title,
                            summary = row.summary,
                            icon = row.icon,
                            enabled = rowEnabled(row.rowKey),
                            showArrow = row.onClick != null,
                            onClick = row.onClick,
                        )
                    }
                }
            }
        }
    }

    inputDialogRow?.let { row ->
        InputDialog(
            row = row,
            onConfirm = { newValue ->
                WePrefs.putString(row.configKey, newValue)
                summaryStates[row.configKey] = row.summaryFormatter?.invoke(newValue) ?: if (newValue.isEmpty()) row.baseSummary else "${row.baseSummary}: $newValue"
                WeLogger.d(TAG, "Config changed [${row.configKey}] -> $newValue")
                inputDialogRow = null
            },
            onDismiss = { inputDialogRow = null },
        )
    }

    selectDialogRow?.let { row ->
        SelectDialog(
            row = row,
            onSelect = { value, displayText ->
                WePrefs.putInt(row.configKey, value)
                summaryStates[row.configKey] = displayText
                WeLogger.d(TAG, "Config changed [${row.configKey}] -> $value")
                selectDialogRow = null
            },
            onDismiss = { selectDialogRow = null },
        )
    }
}

// ---------------------------------------------------------------------------
//  Row Elements & Dialog Components Shared Tree
// ---------------------------------------------------------------------------

@Composable
private fun PrefIconSlot(icon: ImageVector?) {
    if (icon == null) return
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(24.dp),
        tint = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.width(16.dp))
}

@Composable
private fun SwitchRow(row: PrefRow.Switch, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
            ) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PrefIconSlot(row.icon)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = row.title, style = MaterialTheme.typography.bodyLarge)
            if (row.summary.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(text = row.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = if (enabled) onCheckedChange else null, enabled = enabled,
            // FIXME: see androidx.compose.ui.platform.AndroidComposeViewAccessibilityDelegateCompat.android.kt line 3484
            modifier = Modifier.semantics(properties = {
                set(SemanticsProperties.StateDescription, "fuckyougoogle")
            }))
    }
}

@Composable
private fun HookSwitchRow(title: String, summary: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (summary.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(text = summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun HookClickableRow(title: String, summary: String, showSwitch: Boolean, checked: Boolean, onCheckedChange: (Boolean) -> Unit, onClick: (Context) -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(context) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = MaterialSymbols.Outlined.Settings,
                    contentDescription = "Configurable",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (summary.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(text = summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (showSwitch) {
            Spacer(Modifier.width(8.dp))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun SimpleRow(title: String, summary: String?, icon: ImageVector?, enabled: Boolean, showArrow: Boolean, onClick: ((Context) -> Unit)?) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .then(
                if (onClick != null && enabled) Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(),
                    onClick = { onClick(context) }
                ) else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PrefIconSlot(icon)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (!summary.isNullOrEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(text = summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (showArrow) {
            Icon(imageVector = MaterialSymbols.Outlined.Keyboard_arrow_right, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InputDialog(row: PrefRow.EditText, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val current = WePrefs.getStringOrDef(row.configKey, row.defaultValue)
    var text by remember { mutableStateOf(current) }
    val keyboardType = when {
        row.inputType and InputType.TYPE_CLASS_NUMBER != 0 -> KeyboardType.Number
        row.inputType and InputType.TYPE_NUMBER_FLAG_DECIMAL != 0 -> KeyboardType.Decimal
        else -> KeyboardType.Text
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(row.title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = if (row.maxLength > 0 && it.length > row.maxLength) it.take(row.maxLength) else it },
                placeholder = { if (row.hint != null) Text(row.hint) },
                singleLine = row.singleLine,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SelectDialog(row: PrefRow.Select, onSelect: (Int, String) -> Unit, onDismiss: () -> Unit) {
    var selected by remember { mutableIntStateOf(WePrefs.getIntOrDef(row.configKey, row.defaultValue)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(row.title) },
        text = {
            Column {
                row.options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = value
                                onSelect(value, label)
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == value, onClick = { selected = value; onSelect(value, label) })
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
