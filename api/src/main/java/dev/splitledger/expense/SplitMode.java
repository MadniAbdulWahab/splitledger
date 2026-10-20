package dev.splitledger.expense;

public enum SplitMode {
    /** Everyone pays the same; participant values must be empty. */
    EQUAL,
    /** Participant value = amount in cents; the values must add up to the expense amount. */
    EXACT,
    /** Participant value = number of shares, e.g. 2 for someone who had two portions. */
    SHARES
}
