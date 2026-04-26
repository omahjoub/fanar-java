package qa.fanar.core;

/**
 * Fanar-side internal failure. Maps to {@link ErrorCode#INTERNAL_SERVER_ERROR} and HTTP 500.
 *
 * <p>Often transient — the built-in retry interceptor handles this by default, though
 * persistent 500s typically warrant human attention.</p>
 *
 * @author Oussama Mahjoub
 */
public final class FanarInternalServerException extends FanarServerException {

    public FanarInternalServerException(String message) {
        super(message, ErrorCode.INTERNAL_SERVER_ERROR, 500);
    }

    public FanarInternalServerException(String message, Throwable cause) {
        super(message, ErrorCode.INTERNAL_SERVER_ERROR, 500, cause);
    }
}
