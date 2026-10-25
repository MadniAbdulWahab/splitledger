package dev.splitledger.insights;

import dev.splitledger.group.GroupService;
import dev.splitledger.security.CurrentUser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class InsightsController {

    private final InsightsService insightsService;
    private final GroupService groupService;

    InsightsController(InsightsService insightsService, GroupService groupService) {
        this.insightsService = insightsService;
        this.groupService = groupService;
    }

    /** Example: {@code /groups/1/insights?months=6} for this month and the five before it. */
    @GetMapping("/groups/{groupId}/insights")
    InsightsService.Insights insights(@PathVariable long groupId, @AuthenticationPrincipal CurrentUser user,
                                      @RequestParam(defaultValue = "6") @Min(1) @Max(24) int months) {
        return insightsService.insights(groupId, groupService.memberIdOf(groupId, user), months);
    }
}
