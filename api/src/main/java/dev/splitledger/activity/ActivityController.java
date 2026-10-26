package dev.splitledger.activity;

import dev.splitledger.group.GroupService;
import dev.splitledger.security.CurrentUser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class ActivityController {

    private final ActivityService activityService;
    private final GroupService groupService;

    ActivityController(ActivityService activityService, GroupService groupService) {
        this.activityService = activityService;
        this.groupService = groupService;
    }

    /**
     * Newest first; pass the returned {@code nextCursor} as {@code cursor} for older entries. Each
     * entry says what it did to the caller's balance ({@code myChangeCents}).
     */
    @GetMapping("/groups/{groupId}/activity")
    ActivityService.Page feed(@PathVariable long groupId, @AuthenticationPrincipal CurrentUser user,
                              @RequestParam(defaultValue = "30") @Min(1) @Max(100) int limit,
                              @RequestParam(required = false) String cursor) {
        ActivityService.Cursor after;
        try {
            after = cursor == null ? null : ActivityService.Cursor.decode(cursor);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cursor is not valid; use nextCursor from the previous page");
        }
        return activityService.feed(groupId, groupService.memberIdOf(groupId, user), limit, after);
    }
}
