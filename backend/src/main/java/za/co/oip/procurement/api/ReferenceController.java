package za.co.oip.procurement.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only reference codes (event types, statuses, procurement categories, industries). */
@Tag(name = "Reference data")
@RestController
@RequestMapping("/api/v1/reference")
public class ReferenceController {

    private final JdbcTemplate jdbc;

    public ReferenceController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ReferenceCodeDto(String code, String label, String description) {
    }

    @Operation(summary = "List reference codes of a type: EVENT_TYPE, OPPORTUNITY_STATUS, PROCUREMENT_CATEGORY, INDUSTRY")
    @GetMapping("/{codeType}")
    public List<ReferenceCodeDto> list(@PathVariable String codeType) {
        return jdbc.query("select code, label, description from reference_codes where code_type = ? order by sort_order, label",
                (rs, i) -> new ReferenceCodeDto(rs.getString(1), rs.getString(2), rs.getString(3)),
                codeType.toUpperCase());
    }
}
