package bg.spotyourslot.publicprofile.web;

import bg.spotyourslot.publicprofile.application.PublicProfileService;
import bg.spotyourslot.publicprofile.web.PublicProfileHttpRecords.PublicProfileResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The only unauthenticated Business route: a read-only GET that writes nothing. */
@RestController
@RequestMapping("/api/public/businesses")
public class PublicProfileController {
    private final PublicProfileService profiles;

    public PublicProfileController(PublicProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping("/{slug}")
    public PublicProfileResponse get(@PathVariable String slug) {
        return profiles.findBySlug(slug)
                .map(PublicProfileResponse::from)
                .orElseThrow(PublicBusinessUnavailable::new);
    }
}
