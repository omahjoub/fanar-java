/**
 * The request plumbing shared by every domain facade: chain assembly (retry → bearer token →
 * user interceptors → transport), the per-call transport attributes and the trip through the
 * chain — see {@link qa.fanar.core.internal.dispatch.Dispatcher}.
 *
 * <p>Internal per ADR-018 — nothing in this package is exported.</p>
 *
 * @author Oussama Mahjoub
 */
package qa.fanar.core.internal.dispatch;
