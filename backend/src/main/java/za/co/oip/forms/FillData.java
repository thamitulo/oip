package za.co.oip.forms;

import za.co.oip.companies.CompanyDirectorService.DirectorForForm;

import java.util.List;
import java.util.Map;

/**
 * The values available to fill forms with.
 *
 * @param values            formatted company details by field; absent when the profile has none
 * @param directors         directors / members in profile order
 * @param idNumbersWithheld the user may not see identity numbers, so director ID cells are left empty
 */
public record FillData(Map<FormField, String> values, List<DirectorForForm> directors, boolean idNumbersWithheld) {

    String value(FormField f) {
        String v = values.get(f);
        return v == null || v.isBlank() ? null : v;
    }
}
