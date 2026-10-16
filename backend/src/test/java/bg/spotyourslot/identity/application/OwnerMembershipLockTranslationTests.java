package bg.spotyourslot.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import bg.spotyourslot.identity.SelectedBusinessOwnerAccess;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess.Authorization;
import bg.spotyourslot.identity.infrastructure.IdentityStore;
import java.sql.SQLException;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * The owning module translates a PostgreSQL serialization failure or deadlock of the Membership lock
 * into a sanitized, cause-free published conflict, and every other failure into a fixed failure
 * (ADR-0029, Phase 4). No SQLState, SQL, driver, or Spring persistence exception crosses the boundary.
 */
class OwnerMembershipLockTranslationTests {
    private static final String SENTINEL = "SENTINEL-SQL-TEXT-42";

    private final IdentityStore store = mock(IdentityStore.class);
    private final BusinessAuthorizer authorizer = new BusinessAuthorizer(store);

    static Stream<String> conflicts() {
        return Stream.of("40001", "40P01");
    }

    static Stream<String> others() {
        return Stream.of("23505", "25P02", "08006", "57014", "XX000");
    }

    private void storeThrows(DataAccessException failure) {
        when(store.lockActiveOwnerMembership(any(), any())).thenThrow(failure);
    }

    @ParameterizedTest
    @MethodSource("conflicts")
    void aSerializationFailureOrDeadlockIsTheSanitizedConflictWithoutACause(String sqlState) {
        storeThrows(new DataAccessResourceFailureException(SENTINEL, new SQLException(SENTINEL, sqlState)));

        assertThatThrownBy(() -> authorizer.lockAndAuthorizeSanitized(UUID.randomUUID(), UUID.randomUUID()))
                .isExactlyInstanceOf(SelectedBusinessOwnerAccess.ConcurrentConflict.class)
                .hasNoCause()
                .hasNoSuppressedExceptions()
                .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain(SENTINEL).doesNotContain(sqlState));
    }

    @ParameterizedTest
    @MethodSource("others")
    void anyOtherSqlStateIsAFixedFailureWithoutACause(String sqlState) {
        storeThrows(new DataAccessResourceFailureException(SENTINEL, new SQLException(SENTINEL, sqlState)));

        assertThatThrownBy(() -> authorizer.lockAndAuthorizeSanitized(UUID.randomUUID(), UUID.randomUUID()))
                .isExactlyInstanceOf(SelectedBusinessOwnerAccess.Failure.class)
                .hasNoCause()
                .hasNoSuppressedExceptions()
                .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain(SENTINEL).doesNotContain(sqlState));
    }

    @Test
    void aFailureWithoutAnSqlExceptionIsAFixedFailure() {
        storeThrows(new DataAccessResourceFailureException(SENTINEL));

        assertThatThrownBy(() -> authorizer.lockAndAuthorizeSanitized(UUID.randomUUID(), UUID.randomUUID()))
                .isExactlyInstanceOf(SelectedBusinessOwnerAccess.Failure.class)
                .hasNoCause();
    }

    @Test
    void grantedAndDeniedAreReturnedAndTheUnsanitizedLockKeepsItsOwnBehavior() {
        when(store.lockActiveOwnerMembership(any(), any())).thenReturn(true, false);
        assertThat(authorizer.lockAndAuthorizeSanitized(UUID.randomUUID(), UUID.randomUUID()))
                .isEqualTo(Authorization.GRANTED);
        assertThat(authorizer.lockAndAuthorizeSanitized(UUID.randomUUID(), UUID.randomUUID()))
                .isEqualTo(Authorization.DENIED);

        storeThrows(new DataAccessResourceFailureException(SENTINEL, new SQLException(SENTINEL, "40001")));
        // The older capability is unchanged for its other callers: the persistence exception is not translated.
        assertThatThrownBy(() -> authorizer.lockAndAuthorize(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);
    }
}
