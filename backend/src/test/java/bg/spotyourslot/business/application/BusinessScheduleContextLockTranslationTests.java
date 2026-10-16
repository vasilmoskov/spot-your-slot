package bg.spotyourslot.business.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import bg.spotyourslot.business.BusinessScheduleContextAccess;
import bg.spotyourslot.business.infrastructure.BusinessStore;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;

/**
 * The owning module translates a PostgreSQL serialization failure or deadlock of the Business lock
 * into a sanitized, cause-free published conflict, and every other failure into a fixed failure
 * (ADR-0029, Phase 4). No SQLState, SQL, driver, or Spring persistence exception crosses the boundary.
 */
class BusinessScheduleContextLockTranslationTests {
    private static final String SENTINEL = "SENTINEL-SQL-TEXT-42";

    private final BusinessStore store = mock(BusinessStore.class);
    private final BusinessAdministrationService service = new BusinessAdministrationService(
            store, mock(BusinessInputValidator.class), Clock.systemUTC());

    static Stream<String> conflicts() {
        return Stream.of("40001", "40P01");
    }

    static Stream<String> others() {
        return Stream.of("23505", "25P02", "08006", "57014", "XX000");
    }

    private void storeThrows(DataAccessException failure) {
        when(store.findByIdForShare(any())).thenThrow(failure);
    }

    @ParameterizedTest
    @MethodSource("conflicts")
    void aSerializationFailureOrDeadlockIsTheSanitizedConflictWithoutACause(String sqlState) {
        storeThrows(new DataAccessResourceFailureException(SENTINEL, new SQLException(SENTINEL, sqlState)));

        assertThatThrownBy(() -> service.lockScheduleContextSanitized(UUID.randomUUID()))
                .isExactlyInstanceOf(BusinessScheduleContextAccess.ConcurrentConflict.class)
                .hasNoCause()
                .hasNoSuppressedExceptions()
                .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain(SENTINEL).doesNotContain(sqlState));
    }

    @ParameterizedTest
    @MethodSource("conflicts")
    void aNestedSqlExceptionIsFoundInTheCauseChain(String sqlState) {
        storeThrows(new TransientDataAccessResourceException(
                SENTINEL, new RuntimeException(new RuntimeException(new SQLException(SENTINEL, sqlState)))));

        assertThatThrownBy(() -> service.lockScheduleContextSanitized(UUID.randomUUID()))
                .isExactlyInstanceOf(BusinessScheduleContextAccess.ConcurrentConflict.class);
    }

    @ParameterizedTest
    @MethodSource("others")
    void anyOtherSqlStateIsAFixedFailureWithoutACause(String sqlState) {
        storeThrows(new DataAccessResourceFailureException(SENTINEL, new SQLException(SENTINEL, sqlState)));

        assertThatThrownBy(() -> service.lockScheduleContextSanitized(UUID.randomUUID()))
                .isExactlyInstanceOf(BusinessScheduleContextAccess.Failure.class)
                .hasNoCause()
                .hasNoSuppressedExceptions()
                .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain(SENTINEL).doesNotContain(sqlState));
    }

    @Test
    void aFailureWithoutAnSqlExceptionIsAFixedFailure() {
        storeThrows(new DataAccessResourceFailureException(SENTINEL));

        assertThatThrownBy(() -> service.lockScheduleContextSanitized(UUID.randomUUID()))
                .isExactlyInstanceOf(BusinessScheduleContextAccess.Failure.class)
                .hasNoCause();
    }

    @Test
    void aMissingBusinessIsEmptyAndTheUnsanitizedLockKeepsItsOwnBehavior() {
        when(store.findByIdForShare(any())).thenReturn(Optional.empty());
        assertThat(service.lockScheduleContextSanitized(UUID.randomUUID())).isEmpty();

        storeThrows(new DataAccessResourceFailureException(SENTINEL, new SQLException(SENTINEL, "40001")));
        // The older capability is unchanged for its other callers: the persistence exception is not translated.
        assertThatThrownBy(() -> service.lockScheduleContext(UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void theConflictAndTheFailureAreFixedAndFinal() {
        assertThat(new BusinessScheduleContextAccess.ConcurrentConflict().getMessage())
                .isEqualTo("Business schedule context lock conflicted with a concurrent change");
        assertThat(new BusinessScheduleContextAccess.Failure().getMessage())
                .isEqualTo("Business schedule context lock failed");
        assertThat(new BusinessScheduleContextAccess.ConcurrentConflict().getStackTrace()).isEmpty();
    }
}
