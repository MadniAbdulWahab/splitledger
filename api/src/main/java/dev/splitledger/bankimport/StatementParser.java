package dev.splitledger.bankimport;

import java.util.List;

/** One implementation per bank export format. Parsers only read; they never decide what to import. */
public interface StatementParser {

    StatementFormat format();

    /**
     * @throws dev.splitledger.common.BusinessRuleException if the file is not in this format at all
     */
    Result parse(byte[] content);

    record Result(List<ParsedTransaction> transactions, List<SkippedRow> unreadableRows) {
    }

    record SkippedRow(int line, String reason) {
    }
}
