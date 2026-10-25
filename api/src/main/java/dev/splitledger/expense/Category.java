package dev.splitledger.expense;

/**
 * What an expense was for. The web page suggests one from the description or the bank payee
 * ("REWE" is groceries); the person adding the expense can always change it.
 */
public enum Category {
    GROCERIES, FOOD, RENT, UTILITIES, TRANSPORT, TRAVEL, ENTERTAINMENT, SHOPPING, SUBSCRIPTIONS, GIFTS, OTHER
}
