package bg.spotyourslot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bg.spotyourslot.customer.application.CustomerSqlRecording;
import bg.spotyourslot.identity.domain.TokenCodec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * Common setup and problem assertions of the private Customer API integration tests. The clock is
 * controllable and the Customer SQL is counted. Data is synthetic and the database is a
 * Testcontainers instance.
 */
@AutoConfigureMockMvc
@Import(CustomerSqlRecording.class)
abstract class BusinessCustomerApiIntegrationTest extends PostgresIntegrationTest {
    static final Instant NOW = CustomerSqlRecording.NOW;
    static final String NAME = "Анна Иванова";
    static final String PHONE = "+359895555777";
    static final String OTHER_PHONE = "+359888123456";
    static final String EMAIL = "anna@example.test";
    static final String OTHER_EMAIL = "boris@example.test";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired TokenCodec tokens;
    @Autowired MutableTestClock clock;

    BusinessCustomerApiTestSupport api;

    @BeforeEach
    void resetFixtures() {
        // Plain JDBC rather than @Sql: the Customer SQL-recording DataSource wraps the application's.
        jdbc.sql("TRUNCATE business,app_user CASCADE").update();
        clock.set(NOW);
        api = new BusinessCustomerApiTestSupport(mvc, jdbc, tokens, NOW);
        CustomerSqlRecording.clear();
    }

    static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    static JsonNode json(ResultActions result) throws Exception {
        return BusinessCustomerApiTestSupport.parse(body(result));
    }

    static List<String> keys(JsonNode node) {
        return new ArrayList<>(node.propertyNames());
    }

    static void assertValidationWithoutFields(ResultActions result) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.detail").value("Проверете въведените данни."))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    static void assertValidationFields(ResultActions result, String... fields) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.detail").value("Проверете въведените данни."))
                .andExpect(jsonPath("$.fieldErrors.length()").value(fields.length));
        for (String field : fields) {
            result.andExpect(jsonPath("$.fieldErrors." + field).isNotEmpty());
        }
    }

    static void assertUnauthenticated(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    static void assertActiveBusinessRequired(ResultActions result) throws Exception {
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACTIVE_BUSINESS_REQUIRED"))
                .andExpect(jsonPath("$.detail").value("Изберете бизнес, за да продължите."));
    }

    static void assertAccessDenied(ResultActions result) throws Exception {
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.detail").value("Нямате достъп до тази операция."));
    }

    static void assertNotFound(ResultActions result) throws Exception {
        result.andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Клиентът не е намерен."));
    }

    static void assertBusinessSuspended(ResultActions result) throws Exception {
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_SUSPENDED"))
                .andExpect(jsonPath("$.detail").value("Спрян бизнес може само да преглежда данните си."));
    }

    static void assertConcurrentUpdate(ResultActions result) throws Exception {
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CUSTOMER_CONCURRENT_UPDATE"))
                .andExpect(jsonPath("$.detail").value(
                        "Данните за клиента са променени. Обновете данните и опитайте отново."));
    }

    static void assertContactConflict(ResultActions result, boolean phone, boolean email)
            throws Exception {
        result.andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CUSTOMER_CONTACT_CONFLICT"))
                .andExpect(jsonPath("$.fieldErrors.length()").value((phone ? 1 : 0) + (email ? 1 : 0)));
        if (phone) {
            result.andExpect(jsonPath("$.fieldErrors.phone")
                    .value("Този телефонен номер вече е записан за друг клиент."));
        } else {
            result.andExpect(jsonPath("$.fieldErrors.phone").doesNotExist());
        }
        if (email) {
            result.andExpect(jsonPath("$.fieldErrors.email")
                    .value("Този имейл адрес вече е записан за друг клиент."));
        } else {
            result.andExpect(jsonPath("$.fieldErrors.email").doesNotExist());
        }
        result.andExpect(content().string(not(containsString("constraint"))))
                .andExpect(content().string(not(containsString("customer_business"))));
    }

    static void assertNoStore(ResultActions result) throws Exception {
        assertThat(result.andReturn().getResponse().getHeader("Cache-Control")).contains("no-store");
    }
}
