package bg.spotyourslot.publicprofile.web;

import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = PublicProfileController.class)
public class PublicProfileExceptionHandler {
    /** One fixed body for every unavailable case. */
    @ExceptionHandler(PublicBusinessUnavailable.class)
    ProblemDetail unavailable() {
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, "Страницата не е налична.");
        problem.setTitle("Заявката не може да бъде изпълнена.");
        problem.setProperty("code", "BUSINESS_PAGE_UNAVAILABLE");
        // Spring would otherwise fill "instance" with the request path, echoing the slug.
        problem.setInstance(URI.create("/api/public/businesses"));
        return problem;
    }
}
