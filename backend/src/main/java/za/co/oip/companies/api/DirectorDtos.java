package za.co.oip.companies.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import za.co.oip.companies.domain.CompanyDirector.IdType;
import za.co.oip.companies.domain.CompanyDirector.Role;

import java.math.BigDecimal;
import java.util.UUID;

public final class DirectorDtos {

    private DirectorDtos() {
    }

    public record DirectorRequest(
            @NotBlank @Size(max = 200) String fullName,
            @NotNull Role role,
            IdType idType,
            @Schema(description = "Identity or passport number. Omit (null) to keep the stored one; empty string removes it.")
            @Size(max = 30) String idNumber,
            @Pattern(regexp = "^(\\d{10})?$", message = "must be 10 digits") String incomeTaxNumber,
            @DecimalMin("0") @DecimalMax("100") BigDecimal shareholdingPercentage,
            Boolean inServiceOfState) {
    }

    /** Identity numbers are never returned in full: {@code idNumberMasked} shows only the last four characters. */
    public record DirectorDto(UUID id, String fullName, Role role, IdType idType, String idNumberMasked,
                              String incomeTaxNumber, BigDecimal shareholdingPercentage, boolean inServiceOfState) {
    }
}
