package bg.spotyourslot.workforce.infrastructure;

import java.util.UUID;

/** The explicit column list of the public StaffMember read; no contact data or lifecycle data. */
public record PublicStaffRow(UUID id, String displayName) {
}
