package com.mitas.ppnam.station2aa.domain.model

import com.mitas.ppnam.station2aa.data.mqtt.dto.ActiveJobCardSummary

/**
 * One page of the active collection queue (contract v4.1 keyset paging).
 *
 * [snapshotRevision] is the queue version this page was read at. An `active_job_cards_invalidated`
 * push carries a newer revision, which is the signal to drop [nextContinuationToken] and start
 * again from page one — a token minted against an older revision is answered `page_cursor_stale`.
 */
data class ActiveJobsPage(
    val jobs: List<ActiveJobCardSummary> = emptyList(),
    /** Total rows across all pages; null when Station 2 did not supply one. */
    val totalCount: Int? = null,
    val hasMore: Boolean = false,
    val nextContinuationToken: String? = null,
    val snapshotRevision: String? = null,
    /**
     * True when Station 2 rejected our cursor as stale.
     *
     * Modelled as page state rather than an error because it is not a failure the operator can act
     * on or needs to see — the queue simply moved under us, and the correct response is to reload
     * page one. Surfacing it as an exception would put "page_cursor_stale" on a factory handheld.
     */
    val cursorStale: Boolean = false,
) {
    /** True when another page can be requested with [nextContinuationToken]. */
    val canLoadMore: Boolean get() = hasMore && !nextContinuationToken.isNullOrBlank()

    companion object {
        val CURSOR_STALE = ActiveJobsPage(cursorStale = true)

        /** Station 2's default when `pageSize` is omitted; max is 100. */
        const val DEFAULT_PAGE_SIZE = 25
        const val MAX_PAGE_SIZE = 100
    }
}
