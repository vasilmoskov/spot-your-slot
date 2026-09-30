/**
 * The unauthenticated, read-only public Business profile (ADR-0017). Orchestrates the narrow
 * published Business and Catalog read contracts and owns the public HTTP contract. It depends on
 * {@code business} and {@code catalog} only; neither depends back on it.
 */
package bg.spotyourslot.publicprofile;
