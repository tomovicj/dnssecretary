package rs.port53.dnssecretary.model;

/** Who has to act to fix an error. Matches the "fix type" column of the catalogue sheet. */
public enum FixSide {
    /** We fix it in the zone (re-sign, new key, new settings). Sheet: "automatic". */
    ZONE,
    /** The DS at the parent / registrar. Sheet: "instructions". */
    PARENT,
    /** Server configuration; we only report and explain. Sheet: "detection only". */
    OPERATOR
}
