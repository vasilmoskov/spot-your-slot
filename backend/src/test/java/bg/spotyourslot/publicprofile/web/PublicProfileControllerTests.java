package bg.spotyourslot.publicprofile.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bg.spotyourslot.publicprofile.application.PublicProfileService;
import bg.spotyourslot.publicprofile.application.PublicProfileView;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class PublicProfileControllerTests {
    @Mock
    PublicProfileService profiles;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new PublicProfileController(profiles))
                .setControllerAdvice(new PublicProfileExceptionHandler())
                .build();
    }

    @Test
    void serializesTheApprovedAllowlistExactly() throws Exception {
        when(profiles.findBySlug("studio-a")).thenReturn(Optional.of(new PublicProfileView(
                "studio-a", "Студио А", "HAIR_SALON", null, "+359 88 000 0000",
                new PublicProfileView.Address("София", "1000", "Улица", "1", null),
                List.of(new PublicProfileView.Service(
                        java.util.UUID.fromString("00000000-0000-0000-0000-0000000000a1"),
                        "Услуга", null, 45, new BigDecimal("25.00"))))));

        mvc.perform(get("/api/public/businesses/studio-a"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$.slug").value("studio-a"))
                .andExpect(jsonPath("$.description").doesNotExist())
                .andExpect(jsonPath("$.address.length()").value(5))
                .andExpect(jsonPath("$.services[0].length()").value(5))
                .andExpect(jsonPath("$.services[0].id").value("00000000-0000-0000-0000-0000000000a1"))
                .andExpect(jsonPath("$.services[0].durationMinutes").value(45))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"price\":25.00")));
    }

    @Test
    void anAbsentProfileIsTheFixedUnavailableProblem() throws Exception {
        when(profiles.findBySlug("Whatever-Slug")).thenReturn(Optional.empty());

        var response = mvc.perform(get("/api/public/businesses/Whatever-Slug"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BUSINESS_PAGE_UNAVAILABLE"))
                .andExpect(jsonPath("$.title").value("Заявката не може да бъде изпълнена."))
                .andExpect(jsonPath("$.detail").value("Страницата не е налична."))
                .andExpect(jsonPath("$.instance").value("/api/public/businesses"))
                .andReturn().getResponse();

        assertThat(response.getContentAsString()).doesNotContain("Whatever");
    }
}
