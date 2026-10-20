package dev.splitledger.search;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;

@RestController
class ExpenseSearchController {

    private final ExpenseSearchService searchService;

    ExpenseSearchController(ExpenseSearchService searchService) {
        this.searchService = searchService;
    }

    /**
     * Example: {@code /groups/1/expenses/search?q=einkauf lidl&member=2&from=2026-03-01&to=2026-03-31}.
     * Without {@code q} all expenses matching the filters are returned, newest id first. Pass the
     * returned {@code nextCursor} as {@code cursor} to get the next page.
     */
    @GetMapping("/groups/{groupId}/expenses/search")
    ExpenseSearchService.Page search(
            @PathVariable long groupId,
            @RequestParam(required = false) @Size(max = 200) String q,
            @RequestParam(required = false) Long member,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @PositiveOrZero Long min,
            @RequestParam(required = false) @PositiveOrZero Long max,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) String cursor) {
        Cursor after;
        try {
            after = cursor == null ? null : Cursor.decode(cursor);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cursor is not valid; use nextCursor from the previous page");
        }
        return searchService.search(new SearchCriteria(groupId, q, member, from, to, min, max, limit, after));
    }
}
