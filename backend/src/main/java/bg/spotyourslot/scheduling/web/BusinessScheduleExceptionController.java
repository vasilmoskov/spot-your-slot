package bg.spotyourslot.scheduling.web;

import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.scheduling.ScheduleExceptionAdministration;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.CreateScheduleExceptionCommand;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.ReplaceScheduleExceptionCommand;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.ScheduleExceptionAdministrationDetails;
import bg.spotyourslot.scheduling.web.BusinessScheduleExceptionHttpRecords.CreateScheduleExceptionRequest;
import bg.spotyourslot.scheduling.web.BusinessScheduleExceptionHttpRecords.ReplaceScheduleExceptionRequest;
import bg.spotyourslot.scheduling.web.BusinessScheduleExceptionHttpRecords.ScheduleExceptionListResponse;
import bg.spotyourslot.scheduling.web.BusinessScheduleExceptionHttpRecords.ScheduleExceptionResponse;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/business/schedule-exceptions")
public class BusinessScheduleExceptionController {
    private final ScheduleExceptionAdministration exceptions;

    public BusinessScheduleExceptionController(ScheduleExceptionAdministration exceptions) {
        this.exceptions = exceptions;
    }

    @GetMapping
    public ScheduleExceptionListResponse list(
            @CurrentSecurityContext(expression = "authentication")
                    AuthenticatedBusinessContext context,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ScheduleExceptionListResponse.from(exceptions.list(
                context,
                BusinessScheduleExceptionHttpRecords.parseQueryDate(from),
                BusinessScheduleExceptionHttpRecords.parseQueryDate(to)));
    }

    @GetMapping("/{exceptionId}")
    public ScheduleExceptionResponse get(
            @CurrentSecurityContext(expression = "authentication")
                    AuthenticatedBusinessContext context,
            @PathVariable UUID exceptionId) {
        return ScheduleExceptionResponse.from(exceptions.get(context, exceptionId));
    }

    @PostMapping
    public ResponseEntity<ScheduleExceptionResponse> create(
            @CurrentSecurityContext(expression = "authentication")
                    AuthenticatedBusinessContext context,
            @RequestBody CreateScheduleExceptionRequest request) {
        ScheduleExceptionAdministrationDetails created = exceptions.create(
                context,
                new CreateScheduleExceptionCommand(
                        request.kind(),
                        request.staffMemberId(),
                        request.firstDate(),
                        request.lastDate(),
                        request.allDay(),
                        request.toPeriods()));
        URI location = URI.create(
                "/api/business/schedule-exceptions/" + created.exception().id());
        return ResponseEntity.created(location).body(ScheduleExceptionResponse.from(created));
    }

    @PutMapping("/{exceptionId}")
    public ScheduleExceptionResponse replace(
            @CurrentSecurityContext(expression = "authentication")
                    AuthenticatedBusinessContext context,
            @PathVariable UUID exceptionId,
            @RequestBody ReplaceScheduleExceptionRequest request) {
        return ScheduleExceptionResponse.from(exceptions.replace(
                context,
                exceptionId,
                new ReplaceScheduleExceptionCommand(
                        request.expectedVersion(),
                        request.firstDate(),
                        request.lastDate(),
                        request.allDay(),
                        request.toPeriods())));
    }

    @DeleteMapping("/{exceptionId}")
    public ResponseEntity<Void> delete(
            @CurrentSecurityContext(expression = "authentication")
                    AuthenticatedBusinessContext context,
            @PathVariable UUID exceptionId,
            @RequestParam(required = false) Long expectedVersion) {
        exceptions.delete(context, exceptionId, expectedVersion);
        return ResponseEntity.noContent().build();
    }
}
