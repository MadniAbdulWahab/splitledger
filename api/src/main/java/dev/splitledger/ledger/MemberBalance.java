package dev.splitledger.ledger;

/** Net position of one member: positive means the group owes them money. */
public record MemberBalance(long memberId, String displayName, long balanceCents) {
}
