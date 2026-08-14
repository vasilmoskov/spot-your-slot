package bg.spotyourslot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@AutoConfigureMockMvc
@ActiveProfiles(value = "prod", inheritProfiles = false)
@TestPropertySource(
        properties = {
            "spotyourslot.security.allowed-origin=https://spotyourslot.bg",
            "server.servlet.session.cookie.secure=true",
            "server.forward-headers-strategy=framework"
        })
class ProductionProfileIntegrationTests extends PostgresIntegrationTest {
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Autowired MockMvc mvc;

    @Test
    void developmentMailboxEndpointIsAbsent() {
        assertThat(mappings.getHandlerMethods().keySet().stream()
                        .flatMap(info -> info.getPatternValues().stream()))
                .noneMatch("/api/dev/mailbox"::equals);
    }

    @Test
    void csrfCookieIsSecureSameSiteAndRootScopedBehindConfiguredProxy() throws Exception {
        var response = mvc.perform(get("/api/auth/csrf")
                        .header("Forwarded", "proto=https;host=spotyourslot.bg"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        String setCookie = response.getHeader("Set-Cookie");
        var csrfCookie = response.getCookie("XSRF-TOKEN");

        assertThat(setCookie)
                .contains("XSRF-TOKEN=")
                .contains("Path=/")
                .contains("Secure");
        assertThat(csrfCookie).isNotNull();
        assertThat(csrfCookie.getSecure()).isTrue();
        assertThat(csrfCookie.getPath()).isEqualTo("/");
        assertThat(csrfCookie.getAttribute("SameSite")).isEqualTo("Lax");
    }
}
