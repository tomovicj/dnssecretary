package rs.port53.dnssecretary.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import rs.port53.dnssecretary.report.Messages;

/** verify: live check after a fix (role 5, docs/predlog-java.md 3.8). Only the interface for now. */
@Command(name = "verify", mixinStandardHelpOptions = true,
        description = "Posle popravke: ponovo proveri zonu uživo i da resolver vraća NOERROR AD.")
final class VerifyCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Parameters(paramLabel = "<zona>")
    String zone;

    @Override
    public Integer call() {
        spec.commandLine().getErr().println(Messages.get("cli.notImplemented", "verify"));
        return Main.EXIT_TOOL_ERROR;
    }
}
