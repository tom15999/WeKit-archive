package dev.ujhhgtg.wekit.ui.content

import android.content.Context
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.hooks.core.BaseHookItem
import dev.ujhhgtg.wekit.hooks.core.ClickableHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItemsProvider
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.utils.WeLogger

class CategorySettingsScreen(
    private val context: Context,
    private val categoryName: String,
) : BasePrefsScreen(categoryName) {

    override fun initPreferences() {
        val targetItems = HookItemsProvider.ALL_HOOK_ITEMS.filter { item ->
            categoryName in item.categories
        }

        if (targetItems.isEmpty()) return

        targetItems.forEach { item ->
            val name = item.name
            val desc = item.description

            when (item) {
                is ClickableHookItem -> addClickableItem(item, name, desc)
                is SwitchHookItem -> addSwitchItem(item, name, desc)
            }
        }
    }

    private fun addSwitchItem(
        item: SwitchHookItem,
        title: String,
        summary: String,
    ) {
        val configKey = item.name
        val initialChecked = WePrefs.getBoolOrFalse(configKey)

        addHookSwitch(
            key = configKey,
            title = title,
            summary = summary,
            initialChecked = initialChecked,
            onBeforeToggle = { checked ->
                val allowed = item.onBeforeToggle(checked, context)
                if (allowed) {
                    WePrefs.putBool(configKey, checked)
                    item.isEnabled = checked
                }
                allowed
            },
            bindCompletionCallback = { callback ->
                item.setToggleCompletionCallback {
                    callback(item.isEnabled)
                }
            },
        )
    }

    private fun addClickableItem(
        item: ClickableHookItem,
        title: String,
        summary: String,
    ) {
        val configKey = item.name
        val initialChecked = WePrefs.getBoolOrFalse(configKey)

        addHookClickable(
            key = configKey,
            title = title,
            summary = summary,
            showSwitch = !item.noSwitchWidget,
            initialChecked = initialChecked,
            onBeforeToggle = { checked ->
                val allowed = item.onBeforeToggle(checked, context)
                if (allowed) {
                    WePrefs.putBool(configKey, checked)
                    item.isEnabled = checked
                }
                allowed
            },
            bindCompletionCallback = { callback ->
                item.setToggleCompletionCallback {
                    callback(item.isEnabled)
                }
            },
            onClick = {
                runCatching {
                    item.onClick(it)
                }.onFailure { WeLogger.e(nameOf(BaseHookItem::class), "failed to execute onClick of ${item.name}") }
            },
        )
    }
}
