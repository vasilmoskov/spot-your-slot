/**
 * Appointments and booking (issue #18, ADR-0022 to ADR-0026). Phase 2 provides only the
 * Appointment persistence with database overlap protection and the real Scheduling busy-interval
 * source; booking orchestration, public contracts, and idempotency computation are later phases.
 */
package bg.spotyourslot.booking;
