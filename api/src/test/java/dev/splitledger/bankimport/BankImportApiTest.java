package dev.splitledger.bankimport;

import com.jayway.jsonpath.JsonPath;
import dev.splitledger.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static dev.splitledger.bankimport.SparkasseCsvCamtParserTest.sample;
import static org.assertj.core.api.Assertions.assertThat;

class BankImportApiTest extends IntegrationTest {

    static final String FILE_A = "sparkasse-csv-camt-2026-03-a.csv";
    static final String FILE_B = "sparkasse-csv-camt-2026-03-b.csv";

    @Autowired
    private BankImportService importService;

    private long groupId;
    private long anna;
    private long ben;
    private MockMvcTester benClient;

    @BeforeEach
    void setUpGroupWithBen() {
        groupId = createGroup("WG");
        anna = myMemberId(groupId);
        MvcTestResult added = addMemberWithInvite(groupId, "Ben");
        ben = longAt(added, "$.member.id");
        benClient = as(userService.register("Ben").token());
        assertThat(benClient.post().uri("/invitations/accept").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\": \"%s\"}".formatted((String) JsonPath.read(body(added), "$.inviteCode"))))
                .hasStatusOk();
    }

    @Test
    void importingTheSameFileTwiceAddsNothingTheSecondTime() throws IOException {
        MvcTestResult first = upload(FILE_A);
        assertThat(first).hasStatus(201);
        assertThat(first).bodyJson().extractingPath("$.rowsRead").isEqualTo(9);
        assertThat(first).bodyJson().extractingPath("$.newTransactions").isEqualTo(8);
        assertThat(first).bodyJson().extractingPath("$.duplicates").isEqualTo(0);
        assertThat(first).bodyJson().extractingPath("$.skipped[0].line").isEqualTo(5);
        assertThat(first).bodyJson().extractingPath("$.skipped[0].reason").isEqualTo("incoming payment");

        MvcTestResult second = upload(FILE_A);
        assertThat(second).bodyJson().extractingPath("$.newTransactions").isEqualTo(0);
        assertThat(second).bodyJson().extractingPath("$.duplicates").isEqualTo(8);

        assertThat(candidates()).hasSize(8);
    }

    @Test
    void overlappingFileOnlyAddsTheNewDaysAndKeepsBothCoffees() throws IOException {
        upload(FILE_A);

        MvcTestResult overlap = upload(FILE_B);

        assertThat(overlap).bodyJson().extractingPath("$.newTransactions").isEqualTo(3);
        assertThat(overlap).bodyJson().extractingPath("$.duplicates").isEqualTo(4);
        assertThat(overlap).bodyJson().extractingPath("$.skippedCount").isEqualTo(2);
        assertThat(overlap).bodyJson().extractingPath("$.skipped[*].reason").asArray()
                .containsExactly("pending (vorgemerkt); import again once it is booked", "incoming payment");

        List<String> names = JsonPath.read(body(mvc.get().uri("/groups/{g}/bank-transactions", groupId).exchange()),
                "$[*].counterpartyName");
        assertThat(names).hasSize(11).startsWith("Lidl Bonn")
                .filteredOn("Cafe Mokka"::equals).hasSize(2);
    }

    @Test
    void confirmingCreatesASearchableExpensePaidByTheAccountOwner() throws IOException {
        upload(FILE_A);
        long drinks = candidateId("Getränke Hoffmann KG");

        MvcTestResult confirmed = confirm(drinks);

        assertThat(confirmed).hasStatus(201);
        assertThat(confirmed).bodyJson().extractingPath("$.payerId").asNumber().isEqualTo((int) anna);
        assertThat(confirmed).bodyJson().extractingPath("$.amountCents").isEqualTo(2790);
        assertThat(confirmed).bodyJson().extractingPath("$.description").isEqualTo("Getränke Hoffmann KG");
        assertThat(confirmed).bodyJson().extractingPath("$.expenseDate").isEqualTo("2026-03-03");
        assertThat(confirmed).bodyJson().extractingPath("$.bankPurpose").isEqualTo("Getränke WG-Party Rechnung 2026-117");
        assertThat(mvc.get().uri("/groups/{g}/balances", groupId))
                .bodyJson().extractingPath("$.balances[*].balanceCents").asArray().containsExactly(1395, -1395);

        assertThat(confirm(drinks)).as("second confirmation").hasStatus(409);
        assertThat(candidates()).hasSize(7);

        // The bank purpose text is searchable, by every member of the group.
        assertThat(benClient.get().uri("/groups/{g}/expenses/search?q=Rechnung", groupId))
                .bodyJson().extractingPath("$.items[0].id").asNumber().isEqualTo((int) idOf(confirmed));
    }

    @Test
    void ignoringDeletesThePersonalDetailsButStillRecognizesTheRowLater() throws IOException {
        upload(FILE_A);
        long rent = candidateId("Hausverwaltung Rheinblick GmbH");

        MvcTestResult ignored = mvc.post().uri("/groups/{g}/bank-transactions/{t}/ignore", groupId, rent).exchange();

        assertThat(ignored).hasStatusOk();
        assertThat(ignored).bodyJson().extractingPath("$.status").isEqualTo("IGNORED");
        assertThat(ignored).bodyJson().extractingPath("$.purpose").isNull();
        assertThat(ignored).bodyJson().extractingPath("$.counterpartyIban").isNull();
        assertThat(upload(FILE_A)).bodyJson().extractingPath("$.duplicates").isEqualTo(8);
        assertThat(candidates()).hasSize(7);
    }

    @Test
    void bankTransactionsArePrivateToTheAccountOwner() throws IOException {
        upload(FILE_A);
        long drinks = candidateId("Getränke Hoffmann KG");

        assertThat(benClient.get().uri("/groups/{g}/bank-transactions", groupId))
                .hasStatusOk().bodyJson().extractingPath("$").asArray().isEmpty();
        assertThat(benClient.post().uri("/groups/{g}/bank-transactions/{t}/ignore", groupId, drinks)).hasStatus(404);
        assertThat(benClient.post().uri("/groups/{g}/bank-transactions/{t}/confirmation", groupId, drinks)
                .contentType(MediaType.APPLICATION_JSON).content(equalSplitBody())).hasStatus(404);
    }

    @Test
    void rejectsStatementsOfSomeoneElsesAccount() throws IOException {
        assertThat(mvc.put().uri("/groups/{g}/members/me/bank-details", groupId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"iban\": \"DE89 3704 0044 0532 0130 00\"}")).hasStatusOk();

        MvcTestResult result = upload(FILE_A);

        assertThat(result).hasStatus(422);
        assertThat(result).bodyJson().extractingPath("$.detail").asString()
                .contains("...4567").contains("...3000");
        assertThat(candidates()).isEmpty();

        mvc.put().uri("/groups/{g}/members/me/bank-details", groupId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"iban\": \"DE81000010000001234567\"}").exchange();
        assertThat(upload(FILE_A)).hasStatus(201);
    }

    @Test
    void rejectsFilesInAnotherFormat() {
        MvcTestResult result = mvc.post().uri("/groups/{g}/imports", groupId)
                .multipart().file("file", "Date,Amount\n2026-03-01,-5.00\n".getBytes())
                .exchange();

        assertThat(result).hasStatus(422);
        assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("Not a Sparkasse CSV-CAMT export");
    }

    @Test
    void parallelUploadsOfOneFileStoreEachTransactionOnce() throws Exception {
        byte[] content = sample(FILE_A);
        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<BankImportDtos.ImportReport>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return importService.importStatement(groupId, anna, StatementFormat.SPARKASSE_CSV_CAMT, content);
                }));
            }
            start.countDown();
            int created = 0;
            int duplicates = 0;
            for (Future<BankImportDtos.ImportReport> result : results) {
                created += result.get().newTransactions();
                duplicates += result.get().duplicates();
            }
            assertThat(created).isEqualTo(8);
            assertThat(duplicates).isEqualTo(8 * (threads - 1));
        }
        assertThat(jdbc.queryForObject("select count(*) from bank_transaction where group_id = ?", Long.class, groupId))
                .isEqualTo(8);
    }

    @Test
    void sharingCanChangePayerAmountAndDate() throws IOException {
        upload(FILE_A);
        long drinks = candidateId("Getränke Hoffmann KG");

        // Only 20,00 of the 27,90 receipt was for the group, Ben paid Anna back in cash at the
        // till, and it is entered for the party date.
        MvcTestResult confirmed = mvc.post().uri("/groups/{g}/bank-transactions/{t}/confirmation", groupId, drinks)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"description": "Party drinks", "splitMode": "EQUAL",
                         "participants": [{"memberId": %d}, {"memberId": %d}],
                         "payerId": %d, "amountCents": 2000, "expenseDate": "2026-03-05"}
                        """.formatted(anna, ben, ben))
                .exchange();

        assertThat(confirmed).hasStatus(201);
        assertThat(confirmed).bodyJson().extractingPath("$.payerId").asNumber().isEqualTo((int) ben);
        assertThat(confirmed).bodyJson().extractingPath("$.amountCents").isEqualTo(2000);
        assertThat(confirmed).bodyJson().extractingPath("$.expenseDate").isEqualTo("2026-03-05");
        assertThat(confirmed).bodyJson().extractingPath("$.description").isEqualTo("Party drinks");
        assertThat(confirmed).bodyJson().extractingPath("$.bankPurpose").isEqualTo("Getränke WG-Party Rechnung 2026-117");
        assertThat(candidates()).hasSize(7);
    }

    private MvcTestResult upload(String sampleName) throws IOException {
        return mvc.post().uri("/groups/{g}/imports", groupId)
                .multipart().file("file", sample(sampleName))
                .exchange();
    }

    private List<Object> candidates() {
        return JsonPath.read(body(mvc.get().uri("/groups/{g}/bank-transactions", groupId).exchange()), "$[*]");
    }

    private long candidateId(String counterparty) {
        List<Number> ids = JsonPath.read(body(mvc.get().uri("/groups/{g}/bank-transactions", groupId).exchange()),
                "$[?(@.counterpartyName == '" + counterparty + "')].id");
        return ids.getFirst().longValue();
    }

    private MvcTestResult confirm(long transactionId) {
        return mvc.post().uri("/groups/{g}/bank-transactions/{t}/confirmation", groupId, transactionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(equalSplitBody())
                .exchange();
    }

    private String equalSplitBody() {
        return """
                {"splitMode": "EQUAL", "participants": [{"memberId": %d}, {"memberId": %d}]}
                """.formatted(anna, ben);
    }
}
