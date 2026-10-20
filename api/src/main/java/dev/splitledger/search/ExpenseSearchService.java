package dev.splitledger.search;

import dev.splitledger.group.GroupService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ExpenseSearchService {

    private final ExpenseSearchRepository repository;
    private final GroupService groupService;

    ExpenseSearchService(ExpenseSearchRepository repository, GroupService groupService) {
        this.repository = repository;
        this.groupService = groupService;
    }

    public record Page(List<ExpenseHit> items, String nextCursor) {
    }

    @Transactional(readOnly = true)
    Page search(SearchCriteria criteria) {
        groupService.requireGroup(criteria.groupId());
        List<ExpenseHit> rows = repository.search(criteria);
        if (rows.size() <= criteria.limit()) {
            return new Page(rows, null);
        }
        List<ExpenseHit> page = rows.subList(0, criteria.limit());
        ExpenseHit last = page.getLast();
        return new Page(List.copyOf(page), new Cursor(last.score(), last.id()).encode());
    }
}
