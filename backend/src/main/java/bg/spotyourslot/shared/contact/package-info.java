/**
 * The single backend contact-canonicalization policy shared by every module that stores a
 * person's name, telephone, or email address (StaffMember and Customer). It is an explicitly
 * named interface of the {@code shared} module, so only these primitives are exposed.
 */
@NamedInterface("contact")
package bg.spotyourslot.shared.contact;

import org.springframework.modulith.NamedInterface;
