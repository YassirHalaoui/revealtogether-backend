package revealtogether.websockets.domain;

import com.fasterxml.jackson.annotation.JsonValue;

public enum SessionStatus {
    WAITING("waiting"),
    LIVE("live"),
    ENDED("ended");

    private final String value;

    SessionStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static SessionStatus fromValue(String value) {
        for (SessionStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Invalid session status: " + value);
    }
}
