package dev.splitledger.outbox;

import dev.splitledger.group.GroupDtos.MemberResponse;
import dev.splitledger.group.GroupService;
import dev.splitledger.ledger.Posting;
import dev.splitledger.outbox.LedgerEvent.EventType;
import dev.splitledger.outbox.LedgerEvent.MemberPosting;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class OutboxService {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final GroupService groupService;

    OutboxService(JdbcTemplate jdbc, JsonMapper json, GroupService groupService) {
        this.jdbc = jdbc;
        this.json = json;
        this.groupService = groupService;
    }

    /**
     * Stores the event in the caller's transaction, after its ledger entries. That transaction
     * holds the group's row lock (LedgerService), so events of one group get increasing ids in
     * exactly the order they commit; the worker relies on that to drop duplicates.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(EventType type, long groupId, long referenceId, LocalDate occurredOn, String description,
                       long amountCents, List<Posting> postings) {
        Map<Long, String> names = groupService.listMembers(groupId).stream()
                .collect(Collectors.toMap(MemberResponse::id, MemberResponse::displayName, (a, b) -> a));
        List<MemberPosting> memberPostings = postings.stream()
                .filter(posting -> posting.amountCents() != 0)
                .map(posting -> new MemberPosting(posting.memberId(), names.get(posting.memberId()), posting.amountCents()))
                .toList();
        LedgerEvent event = new LedgerEvent(type, groupId, referenceId, occurredOn, description, amountCents, memberPostings);
        jdbc.update("insert into outbox_event (group_id, event_type, payload) values (?, ?, ?::jsonb)",
                groupId, type.name(), json.writeValueAsString(event));
    }
}
