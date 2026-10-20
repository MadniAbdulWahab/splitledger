package dev.splitledger.common;

/** The request is well-formed but breaks a domain rule, e.g. exact split amounts not adding up. */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
