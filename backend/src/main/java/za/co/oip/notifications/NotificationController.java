package za.co.oip.notifications;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import za.co.oip.notifications.NotificationService.NotificationDto;
import za.co.oip.notifications.NotificationService.PreferencesDto;
import za.co.oip.security.CurrentUser;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "Notifications")
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @Operation(summary = "My notifications (most recent first)")
    @GetMapping
    public List<NotificationDto> list(@RequestParam(defaultValue = "false") boolean unreadOnly,
                                      @RequestParam(defaultValue = "50") int limit) {
        var u = CurrentUser.require();
        return service.list(u.tenantId(), u.userId(), unreadOnly, Math.clamp(limit, 1, 200));
    }

    @Operation(summary = "Number of unread notifications")
    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount() {
        var u = CurrentUser.require();
        return Map.of("count", service.unreadCount(u.tenantId(), u.userId()));
    }

    @Operation(summary = "Mark a notification as read")
    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@PathVariable UUID id) {
        var u = CurrentUser.require();
        service.markRead(u.tenantId(), u.userId(), id);
    }

    @Operation(summary = "Mark all notifications as read")
    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void readAll() {
        var u = CurrentUser.require();
        service.markAllRead(u.tenantId(), u.userId());
    }

    @Operation(summary = "My notification preferences")
    @GetMapping("/preferences")
    public PreferencesDto preferences() {
        var u = CurrentUser.require();
        return service.preferences(u.tenantId(), u.userId());
    }

    @Operation(summary = "Update my notification preferences")
    @PutMapping("/preferences")
    public PreferencesDto update(@Valid @RequestBody PreferencesDto body) {
        var u = CurrentUser.require();
        return service.updatePreferences(u.tenantId(), u.userId(), body);
    }
}
