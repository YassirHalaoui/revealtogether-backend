package revealtogether.websockets.domain;

import com.fasterxml.jackson.annotation.JsonValue;

public enum VoteOption {
    BOY("boy"),
    GIRL("girl");

    private final String value;

    VoteOption(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static VoteOption fromValue(String value) {
        for (VoteOption option : values()) {
            if (option.value.equalsIgnoreCase(value)) {
                return option;
            }
        }
        throw new IllegalArgumentException("Invalid vote option: " + value);
    }
}
