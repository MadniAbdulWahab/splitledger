package dev.splitledger.group;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface MemberRepository extends JpaRepository<Member, Long> {

    List<Member> findByGroupIdOrderById(Long groupId);

    boolean existsByGroupIdAndDisplayName(Long groupId, String displayName);

    boolean existsByGroupIdAndUserId(Long groupId, Long userId);

    Optional<Member> findByGroupIdAndUserId(Long groupId, Long userId);

    Optional<Member> findByInviteCodeHash(String inviteCodeHash);

    List<Member> findByUserIdOrderByGroupId(Long userId);

    @Query("select m.id from Member m where m.groupId = :groupId")
    Set<Long> findIdsByGroupId(Long groupId);
}
