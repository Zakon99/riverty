package de.riverty.buchungsservice.internal.domain;

/**
 * Ein eindeutiges Merkmal ist bereits vergeben. Trägt die Id des vorhandenen Datensatzes.
 */
public class DuplikatException extends FachlicheException {

    private static final long serialVersionUID = 1L;

    private final Long vorhandeneId;

    public DuplikatException(String message, Long vorhandeneId) {
        super(message);
        this.vorhandeneId = vorhandeneId;
    }

    public Long getVorhandeneId() {
        return vorhandeneId;
    }
}
