package rs.port53.dnssecretary.collect;

/**
 * How a name server handles transports (F2). Truncated answers are visible per query: Response.tc.
 *
 * @param udp         SOA over UDP got an answer
 * @param tcp         SOA over TCP got an answer
 * @param tcpError    why TCP failed, or null
 * @param ednsPayload EDNS UDP size the server advertised (from the UDP SOA answer), or null without EDNS
 */
public record TransportResult(boolean udp, boolean tcp, String tcpError, Integer ednsPayload) {}
