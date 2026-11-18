package za.co.oip.companies;

/** South African identity numbers: 13 digits, YYMMDD birth date first, Luhn check digit last. */
public final class SaIdNumber {

    private SaIdNumber() {
    }

    public static boolean isValid(String id) {
        if (id == null || !id.matches("\\d{13}")) return false;
        int month = Integer.parseInt(id.substring(2, 4));
        int day = Integer.parseInt(id.substring(4, 6));
        if (month < 1 || month > 12 || day < 1 || day > 31) return false;
        int sum = 0;
        for (int i = 0; i < 13; i++) {
            int d = id.charAt(12 - i) - '0';
            if (i % 2 == 1) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
        }
        return sum % 10 == 0;
    }
}
