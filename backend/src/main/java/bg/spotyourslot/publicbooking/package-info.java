/**
 * The unauthenticated, session-independent HTTP adapter of guest booking (ADR-0026): the public
 * booking options, the public availability view, and the booking endpoint. It owns the public HTTP
 * contract and delegates to the published {@code booking} (orchestration and the abuse limiter),
 * {@code scheduling}, {@code business}, and {@code workforce} contracts. Nothing depends on it and it
 * never depends on {@code identity}: Business context comes only from the route.
 */
package bg.spotyourslot.publicbooking;
