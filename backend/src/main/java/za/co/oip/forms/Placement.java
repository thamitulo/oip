package za.co.oip.forms;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * One value to write on a form page. Coordinates are points from the top-left of the visible page.
 *
 * @param field      a {@link FormField} name, {@code DIRECTOR_NAME}/{@code DIRECTOR_ID}/{@code DIRECTOR_TAX}/
 *                   {@code DIRECTOR_SHARE}, or {@code FREE_TEXT}
 * @param source     LABEL (found next to a printed label), TABLE (directors table), ACROFORM (a real form field)
 *                   or MANUAL (placed by the user)
 * @param missing    the form asks for it but the company profile has no value
 * @param acroField  fully qualified AcroForm field name for ACROFORM placements
 * @param ref        the director a DIRECTOR_* placement belongs to; director identity numbers are kept masked in
 *                   drafts and only filled in from the encrypted store when the PDF is generated
 */
public record Placement(
        @NotBlank @Size(max = 40) String id,
        @Min(0) int page,
        @DecimalMin("0") float x,
        @DecimalMin("0") float y,
        @DecimalMin("1") @DecimalMax("2000") float width,
        @DecimalMin("4") @DecimalMax("200") float height,
        @DecimalMin("5") @DecimalMax("24") float fontSize,
        @NotBlank @Size(max = 40) String field,
        @Size(max = 100) String fieldLabel,
        @Size(max = 1000) String value,
        @NotNull @Size(max = 10) String source,
        double confidence,
        @Size(max = 300) String labelText,
        @Size(max = 300) String acroField,
        boolean missing,
        @Size(max = 40) String ref) {

    public static final String FREE_TEXT = "FREE_TEXT";

    Placement withValue(String newValue, boolean nowMissing) {
        return new Placement(id, page, x, y, width, height, fontSize, field, fieldLabel, newValue, source, confidence,
                labelText, acroField, nowMissing, ref);
    }

    Placement withRef(String newRef) {
        return new Placement(id, page, x, y, width, height, fontSize, field, fieldLabel, value, source, confidence,
                labelText, acroField, missing, newRef);
    }
}
