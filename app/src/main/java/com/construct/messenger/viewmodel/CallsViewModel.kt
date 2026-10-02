package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.model.CallHistoryEntry
import com.construct.messenger.data.repository.CallHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class CallHistoryFilter { ALL, MISSED }

data class CallHistorySection(val kind: Kind, val entries: List<CallHistoryEntry>) {
    enum class Kind { TODAY, YESTERDAY, EARLIER, OLDER }
}

data class CallsUiState(
    val filter: CallHistoryFilter = CallHistoryFilter.ALL,
    /** Whether there is anything to clear — under either filter. */
    val hasAny: Boolean = false,
    val sections: List<CallHistorySection> = emptyList(),
)

/** The Calls tab. **Canon:** iOS `CallHistoryView`. */
@HiltViewModel
class CallsViewModel @Inject constructor(
    private val history: CallHistoryRepository,
) : ViewModel() {
    private val filter = MutableStateFlow(CallHistoryFilter.ALL)

    val uiState: StateFlow<CallsUiState> = combine(history.recent, filter) { entries, selected ->
        val shown = if (selected == CallHistoryFilter.MISSED) {
            entries.filter { it.status == CallHistoryEntry.Status.MISSED }
        } else {
            entries
        }
        CallsUiState(selected, entries.isNotEmpty(), CallHistorySections.group(shown, System.currentTimeMillis()))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CallsUiState())

    fun select(value: CallHistoryFilter) {
        filter.value = value
    }

    fun delete(id: String) {
        viewModelScope.launch { history.delete(id) }
    }

    fun clear() {
        viewModelScope.launch { history.clear() }
    }
}

object CallHistorySections {
    /**
     * iOS `sectionKind`: today, yesterday, within the last seven days, older — by the calendar of
     * [zone], newest first, empty sections left out.
     */
    fun group(entries: List<CallHistoryEntry>, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<CallHistorySection> {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val weekAgoMs = Instant.ofEpochMilli(nowMs).atZone(zone).minusDays(7).toInstant().toEpochMilli()
        fun kind(e: CallHistoryEntry): CallHistorySection.Kind {
            val day: LocalDate = Instant.ofEpochMilli(e.startedAtMs).atZone(zone).toLocalDate()
            return when {
                day == today -> CallHistorySection.Kind.TODAY
                day == today.minusDays(1) -> CallHistorySection.Kind.YESTERDAY
                e.startedAtMs >= weekAgoMs -> CallHistorySection.Kind.EARLIER
                else -> CallHistorySection.Kind.OLDER
            }
        }
        val grouped = entries.sortedByDescending { it.startedAtMs }.groupBy(::kind)
        return CallHistorySection.Kind.entries.mapNotNull { k -> grouped[k]?.let { CallHistorySection(k, it) } }
    }
}
