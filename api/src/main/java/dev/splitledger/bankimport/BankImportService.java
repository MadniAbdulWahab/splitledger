package dev.splitledger.bankimport;

import dev.splitledger.bankimport.BankImportDtos.BankTransactionResponse;
import dev.splitledger.bankimport.BankImportDtos.ConfirmRequest;
import dev.splitledger.bankimport.BankImportDtos.ImportReport;
import dev.splitledger.bankimport.StatementParser.SkippedRow;
import dev.splitledger.common.BusinessRuleException;
import dev.splitledger.common.ConflictException;
import dev.splitledger.common.NotFoundException;
import dev.splitledger.expense.ExpenseDtos.CreateExpenseRequest;
import dev.splitledger.expense.ExpenseDtos.ExpenseResponse;
import dev.splitledger.expense.ExpenseService;
import dev.splitledger.group.GroupService;
import dev.splitledger.group.Member;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
public class BankImportService {

    private final Map<StatementFormat, StatementParser> parsers = new EnumMap<>(StatementFormat.class);
    private final BankImportRepository imports;
    private final BankTransactionRepository transactions;
    private final GroupService groupService;
    private final ExpenseService expenseService;
    private final JdbcTemplate jdbc;

    BankImportService(List<StatementParser> parsers, BankImportRepository imports, BankTransactionRepository transactions,
                      GroupService groupService, ExpenseService expenseService, JdbcTemplate jdbc) {
        parsers.forEach(parser -> this.parsers.put(parser.format(), parser));
        this.imports = imports;
        this.transactions = transactions;
        this.groupService = groupService;
        this.expenseService = expenseService;
        this.jdbc = jdbc;
    }

    /**
     * Parses the file in memory, stores every outgoing booked EUR payment that was not imported
     * before as a candidate, and reports what happened to each row. The file is not kept.
     */
    @Transactional
    public ImportReport importStatement(long groupId, long memberId, StatementFormat format, byte[] content) {
        StatementParser parser = parsers.get(format);
        if (parser == null) {
            throw new BusinessRuleException("No parser for format " + format);
        }
        StatementParser.Result parsed = parser.parse(content);
        String ownIban = groupService.member(memberId).map(Member::getIban).orElse(null);

        List<SkippedRow> skipped = new ArrayList<>(parsed.unreadableRows());
        List<ParsedTransaction> importable = new ArrayList<>();
        for (ParsedTransaction t : parsed.transactions()) {
            if (ownIban != null && !ownIban.equals(t.accountIban())) {
                throw new BusinessRuleException("The statement is for account ..." + lastFour(t.accountIban())
                        + ", but your IBAN in this group ends in ..." + lastFour(ownIban)
                        + ". Upload only statements of your own account.");
            }
            if (!t.booked()) {
                skipped.add(new SkippedRow(t.line(), "pending (vorgemerkt); import again once it is booked"));
            } else if (!"EUR".equals(t.currency())) {
                skipped.add(new SkippedRow(t.line(), "currency " + t.currency() + "; only EUR is supported"));
            } else if (t.amountCents() >= 0) {
                skipped.add(new SkippedRow(t.line(), "incoming payment"));
            } else {
                importable.add(t);
            }
        }

        BankImport bankImport = imports.save(new BankImport(groupId, memberId, format));
        List<String> fingerprints = Fingerprints.of(importable);
        int created = 0;
        for (int i = 0; i < importable.size(); i++) {
            if (insertIfNew(groupId, bankImport.getId(), memberId, fingerprints.get(i), importable.get(i))) {
                created++;
            }
        }
        int duplicates = importable.size() - created;
        int rowsRead = parsed.transactions().size() + parsed.unreadableRows().size();
        bankImport.recordCounts(rowsRead, created, duplicates, skipped.size());
        skipped.sort((a, b) -> Integer.compare(a.line(), b.line()));
        return new ImportReport(bankImport.getId(), format, rowsRead, created, duplicates, skipped.size(), skipped);
    }

    /**
     * ON CONFLICT DO NOTHING lets PostgreSQL decide "new or duplicate" atomically: two uploads of
     * the same file at the same moment cannot both insert a row, and neither fails with an error.
     */
    private boolean insertIfNew(long groupId, long importId, long memberId, String fingerprint, ParsedTransaction t) {
        return jdbc.update("""
                insert into bank_transaction (group_id, import_id, member_id, fingerprint, booking_date, amount_cents,
                                              counterparty_name, counterparty_iban, purpose)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (group_id, fingerprint) do nothing
                """, groupId, importId, memberId, fingerprint, t.bookingDate(), t.amountCents(),
                t.counterpartyName(), t.counterpartyIban(), t.purpose()) == 1;
    }

    /** Only the account owner sees their bank transactions, not the rest of the group. */
    @Transactional(readOnly = true)
    public List<BankTransactionResponse> list(long groupId, long memberId, BankTransaction.Status status) {
        return transactions.findByGroupIdAndMemberIdAndStatusOrderByBookingDateDescIdDesc(groupId, memberId, status)
                .stream().map(BankTransactionResponse::from).toList();
    }

    /**
     * Creates the expense (paid by the account owner) and marks the transaction confirmed in one
     * transaction. The row lock makes a double click wait and then get 409 instead of a second
     * expense; the idempotency key "bank-transaction-{id}" would stop a second expense anyway.
     */
    @Transactional
    public ExpenseResponse confirm(long groupId, long memberId, long transactionId, ConfirmRequest request) {
        BankTransaction t = lockOwn(groupId, memberId, transactionId);
        String description = request.description() != null && !request.description().isBlank()
                ? request.description()
                : t.getCounterpartyName() != null ? truncate(t.getCounterpartyName(), 200) : "Bank payment";
        CreateExpenseRequest expense = new CreateExpenseRequest(
                request.payerId() != null ? request.payerId() : memberId,
                request.amountCents() != null ? request.amountCents() : -t.getAmountCents(),
                description, request.notes(),
                request.expenseDate() != null ? request.expenseDate() : t.getBookingDate(),
                request.splitMode(), request.participants());
        ExpenseResponse created = expenseService.createInTransaction(groupId, "bank-transaction-" + t.getId(),
                expense, t.getPurpose());
        t.confirm(created.id());
        return created;
    }

    @Transactional
    public BankTransactionResponse ignore(long groupId, long memberId, long transactionId) {
        BankTransaction t = lockOwn(groupId, memberId, transactionId);
        t.ignore();
        return BankTransactionResponse.from(t);
    }

    private BankTransaction lockOwn(long groupId, long memberId, long transactionId) {
        // Someone else's transaction gives the same 404 as a missing one: no hint that it exists.
        BankTransaction t = transactions.findOwnForUpdate(transactionId, groupId, memberId)
                .orElseThrow(() -> new NotFoundException("Bank transaction " + transactionId + " not found"));
        if (t.getStatus() != BankTransaction.Status.CANDIDATE) {
            throw new ConflictException("Bank transaction " + transactionId + " is already " + t.getStatus());
        }
        return t;
    }

    private static String lastFour(String iban) {
        return iban.substring(Math.max(0, iban.length() - 4));
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
