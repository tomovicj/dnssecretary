package rs.port53.dnssecretary.config;

/**
 * Policy limits used by the checks.
 *
 * @param signatureExpiryWarnDays A6: warn when a signature expires in fewer days than this
 * @param minRsaBits              C3: RSA keys shorter than this are weak
 */
public record Thresholds(int signatureExpiryWarnDays, int minRsaBits) {

    public static Thresholds defaults() {
        return new Thresholds(7, 2048);
    }
}
