package rs.port53.dnssecretary.catalog;

/** The error IDs from the catalogue (catalog/catalog.yaml). Same IDs as the lab zones. */
public enum ErrorId {
    A1, A2, A3, A4, A5, A6, A7,
    B1, B2, B3, B4, B5, B6, B7, B8,
    C1, C2, C3,
    D1, D2, D3, D4, D5, D6,
    E1, E2, E3, E4,
    F1, F2;

    /** The group letter: A signatures, B chain of trust, C algorithms, D denial, E rollover, F servers. */
    public char group() {
        return name().charAt(0);
    }
}
