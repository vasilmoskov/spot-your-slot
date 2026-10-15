package bg.spotyourslot.booking.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.booking.AppointmentFixtures;
import bg.spotyourslot.booking.AppointmentFixtures.Tenant;
import bg.spotyourslot.booking.infrastructure.AppointmentReadStore.DetailRow;
import bg.spotyourslot.booking.infrastructure.AppointmentReadStore.HistoryRow;
import bg.spotyourslot.booking.infrastructure.AppointmentReadStore.LineRow;
import bg.spotyourslot.booking.infrastructure.AppointmentReadStore.ListRow;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;

/**
 * The read statements of the Business calendar against real PostgreSQL, one by one: every statement
 * is scoped to the Business (data of another Business never appears, even for a guessed
 * identifier), the window predicate is the half-open overlap with the 480-minute prefilter, and the
 * order is the documented total order.
 */
@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class AppointmentReadStoreIntegrationTests extends PostgresIntegrationTest {
    private static final Instant FROM = Instant.parse("2026-11-09T22:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-10T22:00:00Z");

    @Autowired AppointmentReadStore store;
    @Autowired JdbcClient jdbc;

    private AppointmentFixtures fixtures;
    private Tenant mine;
    private Tenant foreign;

    @BeforeEach
    void setUp() {
        fixtures = new AppointmentFixtures(jdbc);
        mine = fixtures.tenant();
        foreign = fixtures.tenant();
    }

    private UUID add(Tenant tenant, Instant start, int minutes) {
        Map<String, Object> row = AppointmentFixtures.row(tenant, start, minutes);
        fixtures.insertRow(row);
        return (UUID) row.get("id");
    }

    @Test
    void everyStatementIsScopedToTheBusinessEvenForGuessedIdentifiers() {
        UUID own = add(mine, FROM.plusSeconds(3600), 30);
        UUID other = add(foreign, FROM.plusSeconds(3600), 30);

        assertThat(store.findWindow(mine.business(), FROM, TO, null)).extracting(ListRow::id).containsExactly(own);
        assertThat(store.countWindow(mine.business(), FROM, TO, null)).isEqualTo(1);
        assertThat(store.findWindowPage(mine.business(), FROM, TO, null, 0, 10)).extracting(ListRow::id).containsExactly(own);
        assertThat(store.findWindow(mine.business(), FROM, TO, foreign.staff())).isEmpty();
        assertThat(store.countWindow(mine.business(), FROM, TO, foreign.staff())).isZero();
        assertThat(store.findDetail(mine.business(), other)).isEmpty();
        assertThat(store.findDetail(foreign.business(), other)).isPresent();
        assertThat(store.countHistory(mine.business(), foreign.customer())).isZero();
        assertThat(store.findHistoryPage(mine.business(), foreign.customer(), 0, 10)).isEmpty();
        assertThat(store.findLines(mine.business(), List.of(other))).isEmpty();
        assertThat(store.findLines(foreign.business(), List.of(other))).hasSize(1);
    }

    @Test
    void theWindowIsTheHalfOpenOverlapWithTheBoundedPrefilter() {
        UUID touchesStart = add(withStaff(mine), FROM.minusSeconds(1800), 30);
        UUID overlapsStart = add(withStaff(mine), FROM.minusSeconds(1799), 30);
        UUID touchesEnd = add(withStaff(mine), TO, 30);
        UUID overlapsEnd = add(withStaff(mine), TO.minusSeconds(1), 30);
        UUID longOverlapping = add(withStaff(mine), FROM.minusSeconds(479 * 60L), 480);
        UUID longTouching = add(withStaff(mine), FROM.minusSeconds(480 * 60L), 480);

        List<UUID> ids = store.findWindow(mine.business(), FROM, TO, null).stream().map(ListRow::id).toList();

        assertThat(ids).containsExactlyInAnyOrder(overlapsStart, overlapsEnd, longOverlapping);
        assertThat(ids).doesNotContain(touchesStart, touchesEnd, longTouching);
        assertThat(store.countWindow(mine.business(), FROM, TO, null)).isEqualTo(3);
    }

    private Tenant withStaff(Tenant tenant) {
        return new Tenant(tenant.business(), tenant.service(), fixtures.staffMember(tenant.business(), tenant.service()),
                tenant.customer());
    }

    @Test
    void theOrderIsStartThenIdAndPagingNeverSkipsOrRepeats() {
        UUID high = UUID.fromString("ffffffff-0000-0000-0000-000000000000");
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000009");
        Map<String, Object> first = AppointmentFixtures.row(withStaff(mine), FROM.plusSeconds(60), 30);
        first.put("id", high);
        fixtures.insertRow(first);
        Map<String, Object> second = AppointmentFixtures.row(withStaff(mine), FROM.plusSeconds(60), 30);
        second.put("id", low);
        fixtures.insertRow(second);
        UUID earlier = add(withStaff(mine), FROM.plusSeconds(30), 30);

        assertThat(store.findWindow(mine.business(), FROM, TO, null)).extracting(ListRow::id)
                .containsExactly(earlier, low, high);
        assertThat(store.findWindowPage(mine.business(), FROM, TO, null, 0, 2)).extracting(ListRow::id)
                .containsExactly(earlier, low);
        assertThat(store.findWindowPage(mine.business(), FROM, TO, null, 2, 2)).extracting(ListRow::id)
                .containsExactly(high);
        assertThat(store.findWindowPage(mine.business(), FROM, TO, null, 4, 2)).isEmpty();
    }

    @Test
    void theHistoryIsNewestFirstWithTheIdentifierAsTheDescendingTieBreaker() {
        UUID high = UUID.fromString("ffffffff-0000-0000-0000-000000000000");
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000009");
        for (UUID id : List.of(low, high)) {
            Map<String, Object> row = AppointmentFixtures.row(withStaff(mine), FROM, 30);
            row.put("id", id);
            fixtures.insertRow(row);
        }
        UUID older = add(withStaff(mine), FROM.minusSeconds(86_400L), 30);

        List<HistoryRow> page = store.findHistoryPage(mine.business(), mine.customer(), 0, 10);

        assertThat(page).extracting(HistoryRow::id).containsExactly(high, low, older);
        assertThat(store.countHistory(mine.business(), mine.customer())).isEqualTo(3);
    }

    @Test
    void theLinesComeInAppointmentThenPositionOrderInOneStatementAndAnEmptyRequestReadsNothing() {
        UUID a = add(withStaff(mine), FROM, 30);
        UUID b = add(withStaff(mine), FROM.plusSeconds(3600), 30);

        List<LineRow> lines = store.findLines(mine.business(), List.of(b, a, a));

        assertThat(lines).extracting(LineRow::appointmentId).containsExactlyInAnyOrder(a, b);
        assertThat(lines).extracting(LineRow::position).containsOnly(1);
        assertThat(store.findLines(mine.business(), List.of())).isEmpty();
    }

    @Test
    void theDetailCarriesThePrivateFactsAndTheAttribution() {
        UUID id = add(mine, FROM, 30);
        jdbc.sql("UPDATE appointment SET internal_note = 'бележка', customer_note = 'гост' WHERE id = :id")
                .param("id", id).update();
        fixtures.cancel(id);

        Optional<DetailRow> detail = store.findDetail(mine.business(), id);

        assertThat(detail).isPresent();
        assertThat(detail.get().internalNote()).isEqualTo("бележка");
        assertThat(detail.get().customerNote()).isEqualTo("гост");
        assertThat(detail.get().cancelledBy()).isEqualTo("BUSINESS");
        assertThat(detail.get().cancelledAt()).isNotNull();
        assertThat(detail.get().priceEur()).isEqualByComparingTo("25.00");
        assertThat(detail.get().version()).isZero();
    }
}
