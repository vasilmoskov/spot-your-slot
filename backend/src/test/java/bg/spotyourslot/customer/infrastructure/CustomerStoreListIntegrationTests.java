package bg.spotyourslot.customer.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.customer.application.CustomerSqlRecording;
import bg.spotyourslot.customer.domain.Customer;
import bg.spotyourslot.customer.domain.CustomerProfile;
import bg.spotyourslot.customer.domain.CustomerSearchCriteria;
import bg.spotyourslot.customer.domain.CustomerSortField;
import bg.spotyourslot.customer.domain.NewCustomer;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Customer list, count, and search against real PostgreSQL: ordering and tie-breakers, stable
 * paging, the narrow search semantics, Business scoping, and constant statement counts. All data is
 * synthetic.
 */
@Import(CustomerSqlRecording.class)
class CustomerStoreListIntegrationTests extends PostgresIntegrationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-10-03T08:00:00Z");

    @Autowired CustomerStore store;
    @Autowired JdbcClient jdbc;

    private UUID businessId;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE business CASCADE").update();
        businessId = createBusiness();
        CustomerSqlRecording.clear();
    }

    // ---- ordering -------------------------------------------------------------------------------

    @Test
    void nameOrderUsesTheNormalizedNameThenTheIdInBothDirections() {
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID high = UUID.fromString("00000000-0000-0000-0000-000000000002");
        insert(high, "Борис Петров", "+359895555001", null);
        insert(low, "борис петров", "+359895555002", null);
        UUID first = insert("Анна Иванова", "+359895555003", null);
        UUID last = insert("Цветан Стоянов", "+359895555004", null);

        assertThat(ids(list(CustomerSearchCriteria.none(), 0, 10, CustomerSortField.NAME, true)))
                .containsExactly(first, low, high, last);
        // The primary key flips with the direction; the id tie-breaker stays ascending.
        assertThat(ids(list(CustomerSearchCriteria.none(), 0, 10, CustomerSortField.NAME, false)))
                .containsExactly(last, low, high, first);
    }

    @Test
    void phoneOrderPutsMissingValuesLastInBothDirectionsWithNameTieBreakers() {
        UUID noPhoneB = insert("Борис", null, "b@example.test");
        UUID noPhoneA = insert("Анна", null, "a@example.test");
        UUID lowPhone = insert("Цветан", "+359887000001", null);
        UUID highPhone = insert("Давид", "+359899000009", null);

        assertThat(ids(list(CustomerSearchCriteria.none(), 0, 10, CustomerSortField.PHONE, true)))
                .containsExactly(lowPhone, highPhone, noPhoneA, noPhoneB);
        assertThat(ids(list(CustomerSearchCriteria.none(), 0, 10, CustomerSortField.PHONE, false)))
                .containsExactly(highPhone, lowPhone, noPhoneA, noPhoneB);
    }

    @Test
    void emailOrderPutsMissingValuesLastInBothDirectionsWithNameTieBreakers() {
        UUID noEmailB = insert("Борис", "+359895555011", null);
        UUID noEmailA = insert("Анна", "+359895555012", null);
        UUID aEmail = insert("Цветан", null, "a@example.test");
        UUID zEmail = insert("Давид", null, "z@example.test");

        assertThat(ids(list(CustomerSearchCriteria.none(), 0, 10, CustomerSortField.EMAIL, true)))
                .containsExactly(aEmail, zEmail, noEmailA, noEmailB);
        assertThat(ids(list(CustomerSearchCriteria.none(), 0, 10, CustomerSortField.EMAIL, false)))
                .containsExactly(zEmail, aEmail, noEmailA, noEmailB);
    }

    // ---- paging ---------------------------------------------------------------------------------

    @Test
    void pagesAreStableCoverEveryRowOnceAndEndWithAPartialPage() {
        // Many identical names make the id tie-breaker decisive.
        List<UUID> inserted = new ArrayList<>();
        for (int index = 0; index < 23; index++) {
            inserted.add(insert("Същото Име", String.format("+3598955%05d", 50000 + index), null));
        }

        for (CustomerSortField sort : CustomerSortField.values()) {
            for (boolean ascending : new boolean[] {true, false}) {
                List<UUID> paged = new ArrayList<>();
                paged.addAll(ids(list(CustomerSearchCriteria.none(), 0, 10, sort, ascending)));
                paged.addAll(ids(list(CustomerSearchCriteria.none(), 1, 10, sort, ascending)));
                List<UUID> finalPage = ids(list(CustomerSearchCriteria.none(), 2, 10, sort, ascending));

                assertThat(finalPage).as("%s %s final page", sort, ascending).hasSize(3);
                paged.addAll(finalPage);
                assertThat(paged).as("%s %s", sort, ascending).hasSize(23).doesNotHaveDuplicates();
                assertThat(new HashSet<>(paged)).isEqualTo(new HashSet<>(inserted));
                assertThat(ids(list(CustomerSearchCriteria.none(), 3, 10, sort, ascending))).isEmpty();
                assertThat(ids(list(CustomerSearchCriteria.none(), 0, 50, sort, ascending)))
                        .as("%s %s single page equals concatenated pages", sort, ascending)
                        .isEqualTo(paged);
            }
        }
        assertThat(store.count(businessId, CustomerSearchCriteria.none())).isEqualTo(23L);
    }

    @Test
    void anEmptyBusinessHasAnEmptyPageAndZeroTotal() {
        assertThat(list(CustomerSearchCriteria.none(), 0, 10, CustomerSortField.NAME, true)).isEmpty();
        assertThat(store.count(businessId, CustomerSearchCriteria.none())).isZero();
    }

    // ---- search ---------------------------------------------------------------------------------

    @Test
    void nameSearchIsACaseInsensitiveSubstringOfTheNormalizedName() {
        UUID anna = insert("Анна Иванова", "+359895555021", null);
        insert("Борис Петров", "+359895555022", null);

        assertThat(search("ИВАНО")).containsExactly(anna);
        assertThat(search("анна иван")).containsExactly(anna);
        assertThat(search("  Анна   ИВАНОВА ")).containsExactly(anna);
        assertThat(search("нова")).containsExactly(anna);
        assertThat(search("Стоянов")).isEmpty();
    }

    @Test
    void nameSearchDoesNotFoldAccentsOrTransliterate() {
        insert("Jose Garcia", "+359895555023", null);
        insert("Анна Иванова", "+359895555024", null);

        assertThat(search("José")).isEmpty();
        assertThat(search("Anna Ivanova")).isEmpty();
    }

    @Test
    void emailSearchIsALowerCaseSubstring() {
        UUID byEmail = insert("Клиент Един", null, "client.one@example.test");
        insert("Клиент Две", null, "other@example.test");

        assertThat(search("CLIENT.ONE@")).containsExactly(byEmail);
        assertThat(search("@Example.TEST")).hasSize(2);
        assertThat(search("one@example")).containsExactly(byEmail);
    }

    @Test
    void phoneSearchMatchesAFullNumberExactlyInEveryNotation() {
        UUID target = insert("Телефон Едно", "+359895555777", null);
        insert("Телефон Две", "+359895555778", null);

        assertThat(search("+359895555777")).containsExactly(target);
        assertThat(search("0895 555 777")).containsExactly(target);
        assertThat(search("00359 895 555 777")).containsExactly(target);
    }

    @Test
    void phoneSearchPrefixMatchesPartialPlusDoubleZeroAndLocalInput() {
        UUID first = insert("Префикс Едно", "+359895555777", null);
        UUID second = insert("Префикс Две", "+359895556000", null);
        UUID other = insert("Префикс Три", "+359887123456", null);

        assertThat(search("+3598955")).containsExactlyInAnyOrder(first, second);
        assertThat(search("003598955")).containsExactlyInAnyOrder(first, second);
        assertThat(search("08955")).containsExactlyInAnyOrder(first, second);
        assertThat(search("0887")).containsExactly(other);
        assertThat(search("+3598955560")).containsExactly(second);
        assertThat(search("08999")).isEmpty();
    }

    @Test
    void malformedPhoneLikeTermsNeverBroadenTheSearch() {
        insert("Обикновен Клиент", "+359895555777", "client@example.test");

        for (String term : new String[] {"+", "00", "+%", "+359%", "0_89", "089\\", "+_", "%", "_", "\\"}) {
            assertThat(search(term)).as(term).isEmpty();
        }
    }

    @Test
    void wildcardCharactersAreLiteralInNamesAndEmails() {
        UUID percent = insert("Скидка 50%_х", "+359895555031", null);
        insert("Скидка 50ab", "+359895555032", null);
        UUID backslash = insert("Път C:\\темп", "+359895555033", null);
        UUID underscore = insert("Подчертан", null, "a_b@example.test");
        insert("Подчертан Втори", null, "axb@example.test");

        assertThat(search("50%_")).containsExactly(percent);
        assertThat(search("%")).containsExactly(percent);
        assertThat(search("_х")).containsExactly(percent);
        assertThat(search("\\")).containsExactly(backslash);
        assertThat(search("a_b@")).containsExactly(underscore);
    }

    @Test
    void aTermMatchingSeveralFieldsReturnsEachCustomerOnce() {
        UUID both = insert("Иван", "+359895555041", "ivan@example.test");

        assertThat(search("ivan")).containsExactly(both);
        assertThat(store.count(businessId, CustomerSearchCriteria.fromTerm("ivan"))).isEqualTo(1L);
    }

    @Test
    void searchTotalsAndPagesAgree() {
        for (int index = 0; index < 12; index++) {
            insert("Намерен " + index, String.format("+3598955%05d", 60000 + index), null);
        }
        insert("Друг", "+359895570000", null);
        CustomerSearchCriteria criteria = CustomerSearchCriteria.fromTerm("намерен");

        assertThat(store.count(businessId, criteria)).isEqualTo(12L);
        assertThat(list(criteria, 0, 10, CustomerSortField.NAME, true)).hasSize(10);
        assertThat(list(criteria, 1, 10, CustomerSortField.NAME, true)).hasSize(2);
    }

    // ---- tenant scoping -------------------------------------------------------------------------

    @Test
    void listCountAndSearchNeverCrossBusinesses() {
        UUID other = createBusiness();
        insert("Общо Име", "+359895555051", "same@example.test");
        store.insert(new NewCustomer(UUID.randomUUID(), other, new CustomerProfile(
                "Общо Име", "+359895555051", "same@example.test"), CREATED_AT));
        store.insert(new NewCustomer(UUID.randomUUID(), other, new CustomerProfile(
                "Само чужд", "+359895555052", null), CREATED_AT));

        for (String term : new String[] {"Общо", "same@", "+359895555051", "08955", "Само чужд"}) {
            List<Customer> found = store.list(
                    businessId, CustomerSearchCriteria.fromTerm(term), 0, 50, CustomerSortField.NAME, true);
            assertThat(found).as(term).allMatch(customer -> customer.businessId().equals(businessId));
        }
        assertThat(store.count(businessId, CustomerSearchCriteria.none())).isEqualTo(1L);
        assertThat(search("Само чужд")).isEmpty();
    }

    // ---- statement counts -----------------------------------------------------------------------

    @Test
    void listAndCountEachRunExactlyOneStatementWhateverThePageOrResultSize() {
        for (int index = 0; index < 30; index++) {
            insert("Брой " + index, String.format("+3598955%05d", 70000 + index), null);
        }
        CustomerSqlRecording.clear();

        list(CustomerSearchCriteria.none(), 0, 10, CustomerSortField.NAME, true);
        assertThat(CustomerSqlRecording.statementsOfCurrentThread()).hasSize(1);
        CustomerSqlRecording.clear();

        list(CustomerSearchCriteria.fromTerm("брой"), 0, 50, CustomerSortField.PHONE, false);
        assertThat(CustomerSqlRecording.statementsOfCurrentThread()).hasSize(1);
        CustomerSqlRecording.clear();

        store.count(businessId, CustomerSearchCriteria.fromTerm("брой"));
        assertThat(CustomerSqlRecording.statementsOfCurrentThread()).hasSize(1);
    }

    // ---- helpers --------------------------------------------------------------------------------

    private List<Customer> list(
            CustomerSearchCriteria criteria, int page, int size, CustomerSortField sort, boolean ascending) {
        return store.list(businessId, criteria, page, size, sort, ascending);
    }

    private List<UUID> search(String term) {
        return ids(list(CustomerSearchCriteria.fromTerm(term), 0, 50, CustomerSortField.NAME, true));
    }

    private static List<UUID> ids(List<Customer> customers) {
        return customers.stream().map(Customer::id).toList();
    }

    private UUID insert(String name, String phone, String email) {
        UUID id = UUID.randomUUID();
        insert(id, name, phone, email);
        return id;
    }

    private void insert(UUID id, String name, String phone, String email) {
        store.insert(new NewCustomer(id, businessId, new CustomerProfile(name, phone, email), CREATED_AT));
    }

    private UUID createBusiness() {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.ofInstant(CREATED_AT, ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO business(
                            id, slug, display_name, business_type, status, timezone,
                            created_at, updated_at)
                        VALUES (
                            :id, :slug, 'Customer List Test', 'OTHER', 'DRAFT',
                            'Europe/Sofia', :now, :now)
                        """)
                .param("id", id)
                .param("slug", "customer-list-" + id)
                .param("now", now)
                .update();
        return id;
    }
}
