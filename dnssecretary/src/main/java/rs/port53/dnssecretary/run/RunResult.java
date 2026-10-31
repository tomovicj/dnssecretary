package rs.port53.dnssecretary.run;

import rs.port53.dnssecretary.verify.VerifyResult;

/**
 * How a fix script run ended: in the sandbox (simulation) or on the lab (execution).
 *
 * @param scriptExit the script's exit code; -1 when it never ran
 * @param verify     the verify after the script; null when the script failed or the run broke
 * @param error      why the run itself broke (ERROR), else null
 */
public record RunResult(Outcome outcome, int scriptExit, VerifyResult verify, String error) {

    /**
     * PASS = the script ran and verify is ok (the "verified" column: true);
     * FAIL = the script failed, or the zone is still broken (false);
     * ERROR = the run itself broke (sandbox didn't start, timeout, …), nothing is known about the fix.
     */
    public enum Outcome { PASS, FAIL, ERROR }

    public static RunResult error(String why) {
        return new RunResult(Outcome.ERROR, -1, null, why);
    }

    /** From the script's exit code and the verify that followed it (null if none ran). */
    public static RunResult of(int scriptExit, VerifyResult verify) {
        Outcome o = scriptExit == 0 && verify != null && verify.ok() ? Outcome.PASS : Outcome.FAIL;
        return new RunResult(o, scriptExit, verify, null);
    }
}
