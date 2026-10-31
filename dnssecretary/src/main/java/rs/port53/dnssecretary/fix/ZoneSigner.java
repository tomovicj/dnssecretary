package rs.port53.dnssecretary.fix;

import java.nio.file.Path;
import java.util.List;
import org.xbill.DNS.Name;

/** Signs a zone file and runs external commands. Behind an interface so it can be replaced (or faked in tests). */
public interface ZoneSigner {

    /**
     * Sign {@code input} into {@code output} with the zone's active keys, then verify the result.
     *
     * @param nsec3 NSEC3 without salt and with 0 extra iterations (RFC 9276) instead of NSEC
     */
    void sign(Name zone, Path input, Path output, boolean nsec3) throws FixException;

    /** Run a command (e.g. sign-tld.sh); its output is returned, a non-zero exit is a FixException. */
    String run(List<String> command) throws FixException;
}
