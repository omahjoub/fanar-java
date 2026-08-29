/**
 * Shared, JDK-only test fixtures for the reactor's seam-crossing {@code *IntegrationTest} classes.
 *
 * <p>{@link qa.fanar.testsupport.ScriptedHttpServer} answers requests from a scripted reply queue
 * on a loopback port and fails the test that leaves the script unconsumed;
 * {@link qa.fanar.testsupport.CollectingSubscriber} drains a {@link java.util.concurrent.Flow.Publisher}
 * and lets a test wait, with a timeout, for items or the terminal signal. Neither type depends on
 * {@code fanar-core}, so the core module's own tests can use them without a dependency cycle.</p>
 *
 * <p>Never published — this module exists only for the build.</p>
 *
 * @author Oussama Mahjoub
 */
package qa.fanar.testsupport;
