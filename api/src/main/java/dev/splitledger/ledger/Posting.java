package dev.splitledger.ledger;

/** One side of a business event before it is written: who is credited or debited by how much. */
public record Posting(long memberId, long amountCents) {
}
