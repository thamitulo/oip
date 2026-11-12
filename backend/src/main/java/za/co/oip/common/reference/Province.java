package za.co.oip.common.reference;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;

/** South African provinces. */
public enum Province {
    EASTERN_CAPE("Eastern Cape"),
    FREE_STATE("Free State"),
    GAUTENG("Gauteng"),
    KWAZULU_NATAL("KwaZulu-Natal"),
    LIMPOPO("Limpopo"),
    MPUMALANGA("Mpumalanga"),
    NORTH_WEST("North West"),
    NORTHERN_CAPE("Northern Cape"),
    WESTERN_CAPE("Western Cape");

    private final String displayName;

    Province(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** Lenient lookup that accepts enum names and display names ("KwaZulu-Natal", "kwazulu natal", "KZN"). */
    public static Optional<Province> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String key = Normalizer.normalize(value, Normalizer.Form.NFKC).trim().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z]+", "_").replaceAll("^_|_$", "");
        for (Province p : values()) {
            if (p.name().equals(key)) {
                return Optional.of(p);
            }
        }
        return switch (key) {
            case "KZN" -> Optional.of(KWAZULU_NATAL);
            case "EC" -> Optional.of(EASTERN_CAPE);
            case "FS" -> Optional.of(FREE_STATE);
            case "GP" -> Optional.of(GAUTENG);
            case "LP", "NORTHERN_PROVINCE" -> Optional.of(LIMPOPO);
            case "MP" -> Optional.of(MPUMALANGA);
            case "NW" -> Optional.of(NORTH_WEST);
            case "NC" -> Optional.of(NORTHERN_CAPE);
            case "WC" -> Optional.of(WESTERN_CAPE);
            default -> Optional.empty();
        };
    }
}
