package bg.spotyourslot.identity.web;

import bg.spotyourslot.identity.application.AuthenticationRateLimiter;
import bg.spotyourslot.identity.application.AuthenticationService;
import bg.spotyourslot.identity.application.AuthenticatedUser;
import bg.spotyourslot.identity.application.IdentityRecords.BusinessAccess;
import bg.spotyourslot.identity.application.IdentityRecords.User;
import bg.spotyourslot.identity.application.InvitationService;
import bg.spotyourslot.identity.application.RecoveryService;
import bg.spotyourslot.identity.infrastructure.DatabaseSessionFilter;
import bg.spotyourslot.identity.infrastructure.IdentityStore;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthenticationService authentication;
    private final RecoveryService recovery;
    private final InvitationService invitations;
    private final IdentityStore store;
    private final AuthenticationRateLimiter limiter;
    private final boolean secure;

    public AuthController(
            AuthenticationService authentication,
            RecoveryService recovery,
            InvitationService invitations,
            IdentityStore store,
            AuthenticationRateLimiter limiter,
            @Value("${server.servlet.session.cookie.secure:false}") boolean secure) {
        this.authentication = authentication;
        this.recovery = recovery;
        this.invitations = invitations;
        this.store = store;
        this.limiter = limiter;
        this.secure = secure;
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @PostMapping("/login")
    public SessionView login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse response) {
        limit("login", request.email(), servletRequest);
        var result = authentication.login(request.email(), request.password());
        limiter.reset("login", servletRequest.getRemoteAddr(), request.email());
        response.addCookie(cookie(result.token(), (int) Duration.ofHours(12).toSeconds()));
        return SessionView.of(result.user(), result.businesses(), null);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(AuthenticatedUser user, HttpServletResponse response) {
        authentication.logout(user.sessionId());
        response.addCookie(cookie("", 0));
    }

    @GetMapping("/session")
    public SessionView session(AuthenticatedUser user) {
        return SessionView.of(
                user.user(), store.businesses(user.user().id()), user.activeBusinessId());
    }

    @PostMapping("/business")
    public SessionView select(
            @Valid @RequestBody BusinessRequest request, AuthenticatedUser user) {
        authentication.selectBusiness(
                user.sessionId(), user.user().id(), request.businessId());
        return SessionView.of(
                user.user(), store.businesses(user.user().id()), request.businessId());
    }

    @PostMapping("/profile")
    public SessionView updateProfile(
            @Valid @RequestBody UpdateDisplayNameRequest request, AuthenticatedUser user) {
        var updated = authentication.updateDisplayName(user.user().id(), request.displayName());
        return SessionView.of(
                updated, store.businesses(updated.id()), user.activeBusinessId());
    }

    @PostMapping("/password/change")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void change(
            @Valid @RequestBody ChangePasswordRequest request, AuthenticatedUser user) {
        authentication.changePassword(
                user.sessionId(),
                user.user().id(),
                request.currentPassword(),
                request.newPassword());
    }

    @PostMapping("/password/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> forgot(
            @Valid @RequestBody EmailRequest request, HttpServletRequest servletRequest) {
        limit("forgot", request.email(), servletRequest);
        recovery.request(request.email());
        return Map.of("message", "Ако съществува профил, ще получите инструкции.");
    }

    @PostMapping("/password/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(
            @Valid @RequestBody ResetRequest request, HttpServletRequest servletRequest) {
        limit("reset", request.token(), servletRequest);
        recovery.reset(request.token(), request.password());
    }

    @PostMapping("/invitations/accept")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void accept(
            @Valid @RequestBody AcceptInvitationRequest request,
            HttpServletRequest servletRequest) {
        limit("invitation", request.token(), servletRequest);
        invitations.accept(request.token(), request.displayName(), request.password());
    }

    private void limit(String flow, String sensitiveInput, HttpServletRequest request) {
        if (!limiter.allow(flow, request.getRemoteAddr(), sensitiveInput)) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Твърде много опити. Опитайте по-късно.");
        }
    }

    private Cookie cookie(String value, int age) {
        var cookie = new Cookie(DatabaseSessionFilter.COOKIE, value);
        cookie.setHttpOnly(true);
        cookie.setSecure(secure);
        cookie.setPath("/");
        cookie.setMaxAge(age);
        cookie.setAttribute("SameSite", "Lax");
        return cookie;
    }

    public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {
    }

    public record EmailRequest(@Email @NotBlank String email) {
    }

    public record BusinessRequest(UUID businessId) {
    }

    public record UpdateDisplayNameRequest(@NotBlank @Size(max = 200) String displayName) {
        public UpdateDisplayNameRequest {
            displayName = displayName == null ? null : displayName.trim();
        }
    }

    public record ChangePasswordRequest(
            @NotBlank String currentPassword, @NotBlank String newPassword) {
    }

    public record ResetRequest(@NotBlank String token, @NotBlank String password) {
    }

    public record AcceptInvitationRequest(
            @NotBlank String token,
            @NotBlank String displayName,
            @NotBlank String password) {
    }

    public record SessionView(
            UUID userId,
            String email,
            String displayName,
            boolean platformAdmin,
            List<BusinessAccess> businesses,
            UUID activeBusinessId) {
        static SessionView of(User user, List<BusinessAccess> businesses, UUID active) {
            return new SessionView(
                    user.id(),
                    user.email(),
                    user.displayName(),
                    user.platformAdmin(),
                    businesses,
                    active);
        }
    }
}
