/**
 * The private, owner-only HTTP adapter of the Business calendar and the Customer Appointment
 * history (ADR-0028 sections 11 to 13): four read-only {@code GET} routes. It owns the HTTP
 * contract, the fixed problem details, and the wire shapes, and delegates every decision to the
 * published {@code booking.AppointmentCalendar} contract. It depends only on the published
 * {@code booking} root package and on the published {@code identity} context types, owns no
 * transaction, store, or log, and nothing depends on it.
 */
package bg.spotyourslot.calendar;
