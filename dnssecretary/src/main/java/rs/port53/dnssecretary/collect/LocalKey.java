package rs.port53.dnssecretary.collect;

import org.xbill.DNS.DNSKEYRecord;

/**
 * A public key of the zone found in the configured key directory (source K).
 * Only the public .key file is read, never .private.
 *
 * @param location the subdirectory relative to the key dir: "" (active), "old", "next", "retired", "k2", "alg8" …
 * @param file     the file name, e.g. Ke2-ksk-ds-early.test.+013+12345.key
 */
public record LocalKey(String location, String file, DNSKEYRecord key) {}
