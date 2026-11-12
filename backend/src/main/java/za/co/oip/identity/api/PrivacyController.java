package za.co.oip.identity.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import za.co.oip.identity.PrivacyService;
import za.co.oip.security.CurrentUser;

import java.util.Map;

/** POPIA self-service: export my data, delete my account. */
@Tag(name = "Privacy")
@RestController
@RequestMapping("/api/v1/users/me")
public class PrivacyController {

    private final PrivacyService privacy;

    public PrivacyController(PrivacyService privacy) {
        this.privacy = privacy;
    }

    public record DeleteAccountRequest(String password) {
    }

    @Operation(summary = "Export the personal information held about me (JSON)")
    @GetMapping("/export")
    public Map<String, Object> export() {
        return privacy.export(CurrentUser.require());
    }

    @Operation(summary = "Delete my account (requires my password; anonymises my user record)")
    @PostMapping("/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestBody DeleteAccountRequest body) {
        privacy.deleteAccount(CurrentUser.require(), body.password());
    }
}
