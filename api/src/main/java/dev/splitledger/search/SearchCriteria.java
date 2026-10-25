package dev.splitledger.search;

import java.time.LocalDate;

/** All filters are optional except the group; {@code after} is the decoded cursor of the previous page. */
record SearchCriteria(
        long groupId,
        String text,
        Long memberId,
        LocalDate from,
        LocalDate to,
        Long minCents,
        Long maxCents,
        String category,
        int limit,
        Cursor after) {

    boolean hasText() {
        return text != null && !text.isBlank();
    }
}
