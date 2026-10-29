package com.woori.gyeongjobi.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FilterConfig(
    val enabledAmounts: Set<Long>,
    val customAmounts: Set<Long>,
    val filterByAmountEnabled: Boolean = true
) {
    /**
     * 기본 프리셋 금액 목록:
     * 3만, 5만, 7만, 10만, 15만, 20만, 30만, 50만, 100만원
     */
    companion object {
        val DEFAULT_PRESET_AMOUNTS: List<Long> = listOf(
            30_000L,
            50_000L,
            70_000L,
            100_000L,
            150_000L,
            200_000L,
            300_000L,
            500_000L,
            1_000_000L
        )
    }

    val allAvailableAmounts: List<Long>
        get() = (DEFAULT_PRESET_AMOUNTS + customAmounts).distinct().sorted()

    fun isAmountAllowed(amount: Long): Boolean {
        if (!filterByAmountEnabled) return amount > 0L
        return enabledAmounts.contains(amount)
    }
}

class FilterPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("filter_prefs", Context.MODE_PRIVATE)

    private val _configFlow = MutableStateFlow(loadConfig())
    val configFlow: StateFlow<FilterConfig> = _configFlow.asStateFlow()

    fun currentConfig(): FilterConfig = loadConfig()

    private fun loadConfig(): FilterConfig {
        val enabledRaw = prefs.getStringSet(KEY_ENABLED_AMOUNTS, null)
        val customRaw = prefs.getStringSet(KEY_CUSTOM_AMOUNTS, emptySet()) ?: emptySet()
        val filterEnabled = prefs.getBoolean(KEY_FILTER_BY_AMOUNT_ENABLED, true)

        val customSet = customRaw.mapNotNull { it.toLongOrNull() }.toSet()
        val enabledSet = if (enabledRaw == null) {
            FilterConfig.DEFAULT_PRESET_AMOUNTS.toSet()
        } else {
            enabledRaw.mapNotNull { it.toLongOrNull() }.toSet()
        }

        return FilterConfig(
            enabledAmounts = enabledSet,
            customAmounts = customSet,
            filterByAmountEnabled = filterEnabled
        )
    }

    fun toggleAmount(amount: Long) {
        val current = loadConfig()
        val updated = current.enabledAmounts.toMutableSet()
        if (updated.contains(amount)) {
            updated.remove(amount)
        } else {
            updated.add(amount)
        }
        save(current.copy(enabledAmounts = updated))
    }

    fun addCustomAmount(amount: Long) {
        if (amount <= 0L) return
        val current = loadConfig()
        val updatedCustom = current.customAmounts + amount
        val updatedEnabled = current.enabledAmounts + amount
        save(current.copy(enabledAmounts = updatedEnabled, customAmounts = updatedCustom))
    }

    fun removeCustomAmount(amount: Long) {
        val current = loadConfig()
        val updatedCustom = current.customAmounts - amount
        val updatedEnabled = current.enabledAmounts - amount
        save(current.copy(enabledAmounts = updatedEnabled, customAmounts = updatedCustom))
    }

    fun setFilterByAmountEnabled(enabled: Boolean) {
        val current = loadConfig()
        save(current.copy(filterByAmountEnabled = enabled))
    }

    fun restoreFromBackup(enabledAmounts: Set<Long>, customAmounts: Set<Long>, filterByAmountEnabled: Boolean) {
        save(
            FilterConfig(
                enabledAmounts = enabledAmounts,
                customAmounts = customAmounts,
                filterByAmountEnabled = filterByAmountEnabled
            )
        )
    }

    private fun save(config: FilterConfig) {
        prefs.edit()
            .putStringSet(KEY_ENABLED_AMOUNTS, config.enabledAmounts.map { it.toString() }.toSet())
            .putStringSet(KEY_CUSTOM_AMOUNTS, config.customAmounts.map { it.toString() }.toSet())
            .putBoolean(KEY_FILTER_BY_AMOUNT_ENABLED, config.filterByAmountEnabled)
            .apply()
        _configFlow.value = config
    }

    companion object {
        private const val KEY_ENABLED_AMOUNTS = "enabled_amounts"
        private const val KEY_CUSTOM_AMOUNTS = "custom_amounts"
        private const val KEY_FILTER_BY_AMOUNT_ENABLED = "filter_by_amount_enabled"
    }
}
