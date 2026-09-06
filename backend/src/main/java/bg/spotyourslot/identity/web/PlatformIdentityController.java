package bg.spotyourslot.identity.web;

import bg.spotyourslot.identity.application.AuthenticatedUser;
import bg.spotyourslot.identity.application.DevelopmentMailbox;
import bg.spotyourslot.identity.application.IdentityRecords.DeliveredLink;
import bg.spotyourslot.identity.application.InvitationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/identity")
public class PlatformIdentityController {
    private final InvitationService invitations;

    public PlatformIdentityController(InvitationService invitations) {
        this.invitations = invitations;
    }

    @PostMapping("/businesses/{businessId}/owner-invitation")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void invite(
            @PathVariable UUID businessId,
            @Valid @RequestBody InviteRequest request,
            AuthenticatedUser admin) {
        invitations.invite(businessId, request.email(), admin.user().id());
    }

    public record InviteRequest(@Email @NotBlank String email) {
    }
}

@RestController
@RequestMapping("/api/dev/mailbox")
@Profile({"dev", "test"})
class DevelopmentMailboxController {
    private final DevelopmentMailbox mailbox;

    DevelopmentMailboxController(DevelopmentMailbox mailbox) {
        this.mailbox = mailbox;
    }

    @GetMapping
    List<DeliveredLink> messages() {
        return mailbox.messages();
    }
}
