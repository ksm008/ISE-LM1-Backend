package iselm1.config;

import java.util.UUID;

public final class LabIds {

    private LabIds() {}

    /** 프론트엔드는 labId 를 UUID 로 생성한다. UUID 형식이 아니면 거부한다. */
    public static boolean isValid(String labId) {
        if (labId == null || labId.length() != 36) {
            return false;
        }
        try {
            UUID.fromString(labId);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
