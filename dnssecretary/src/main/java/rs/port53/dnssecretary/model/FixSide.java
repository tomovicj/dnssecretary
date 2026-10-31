package rs.port53.dnssecretary.model;

/** Who has to act to fix an error. Matches "Tip popravke" in the catalogue sheet. */
public enum FixSide {
    /** We fix it in the zone (re-sign, new key, new settings). Sheet: "Auto". */
    ZONE,
    /** The DS at the parent / registrar. Sheet: "Uputstvo". */
    PARENT,
    /** Server configuration; we only report and explain. Sheet: "Detekcija". */
    OPERATOR
}
