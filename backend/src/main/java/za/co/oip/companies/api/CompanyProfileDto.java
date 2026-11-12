package za.co.oip.companies.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import za.co.oip.common.reference.Province;
import za.co.oip.companies.domain.CompanyEnums.BbbeeLevel;
import za.co.oip.companies.domain.CompanyEnums.BusinessSize;
import za.co.oip.companies.domain.CompanyEnums.EnterpriseClassification;
import za.co.oip.companies.domain.CompanyEnums.ReferenceSector;
import za.co.oip.companies.domain.CompanyEnums.RegistrationType;
import za.co.oip.companies.domain.CompanyEnums.VerificationStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Company profile representation used for both reads and full updates (PUT). {@code id},
 * {@code updatedAt} and {@code verificationStatus = VERIFIED} cannot be set by clients.
 */
public record CompanyProfileDto(
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID id,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant updatedAt,
        @NotBlank @Size(max = 300) String legalName,
        @Size(max = 300) String tradingName,
        @Size(max = 50) String registrationNumber,
        @Size(max = 30) String vatNumber,
        @Size(max = 500) @Pattern(regexp = "^(https?://.+)?$", message = "must be an http(s) URL") String website,
        @Size(max = 10_000) String description,
        Province province,
        @Size(max = 120) String city,
        BusinessSize businessSize,
        @Size(max = 40) String annualTurnoverBand,
        BbbeeLevel bbbeeLevel,
        @DecimalMin("0") @DecimalMax("100") BigDecimal blackOwnershipPercentage,
        EnterpriseClassification enterpriseClassification,
        @Size(max = 20) String cidbGrading,
        @Size(max = 10_000) String miningSectorExperience,
        @Size(max = 10_000) String governmentSectorExperience,
        @Size(max = 200) String contactName,
        @Email @Size(max = 320) String contactEmail,
        @Size(max = 40) String contactPhone,
        @Size(max = 100) List<@NotBlank @Size(max = 200) String> industries,
        @Size(max = 200) List<@NotBlank @Size(max = 200) String> services,
        @Size(max = 200) List<@NotBlank @Size(max = 200) String> products,
        @Size(max = 100) List<@NotBlank @Size(max = 200) String> commodities,
        @Schema(description = "Province enum names, or NATIONAL")
        @Size(max = 20) List<@NotBlank @Size(max = 200) String> operatingRegions,
        @Size(max = 100) List<@Valid CertificationDto> certifications,
        @Size(max = 100) List<@Valid RegistrationDto> registrations,
        @Size(max = 100) List<@Valid CapabilityDto> capabilities,
        @Size(max = 200) List<@Valid ReferenceDto> references) {

    public record CertificationDto(@NotBlank @Size(max = 200) String name,
                                   @Size(max = 200) String issuingBody,
                                   @Size(max = 100) String certificateNumber,
                                   LocalDate validUntil,
                                   VerificationStatus verificationStatus) {
    }

    public record RegistrationDto(@NotNull RegistrationType registrationType,
                                  @NotBlank @Size(max = 200) String registrationBody,
                                  @Size(max = 100) String registrationNumber,
                                  LocalDate validUntil,
                                  VerificationStatus verificationStatus) {
    }

    public record CapabilityDto(@NotBlank @Size(max = 200) String name,
                                @Size(max = 5000) String description,
                                @PositiveOrZero @Max(100) Integer yearsExperience) {
    }

    public record ReferenceDto(@NotBlank @Size(max = 300) String clientName,
                               @NotBlank @Size(max = 300) String projectTitle,
                               @Size(max = 5000) String description,
                               ReferenceSector sector,
                               @PositiveOrZero BigDecimal contractValue,
                               @Min(1950) @Max(2100) Integer yearCompleted) {
    }
}
