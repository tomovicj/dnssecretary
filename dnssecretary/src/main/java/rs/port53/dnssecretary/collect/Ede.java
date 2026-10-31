package rs.port53.dnssecretary.collect;

/** Extended DNS Error (RFC 8914) from a response, e.g. 7 "signature expired". Only a hint. */
public record Ede(int code, String text) {}
