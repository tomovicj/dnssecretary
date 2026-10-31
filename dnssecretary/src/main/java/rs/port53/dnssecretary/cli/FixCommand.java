package rs.port53.dnssecretary.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import rs.port53.dnssecretary.report.Messages;

/** fix: plan → apply (role 5, docs/predlog-java.md 3.7). Only the interface for now. */
@Command(name = "fix", mixinStandardHelpOptions = true,
        description = "Popravi zonu. Podrazumevano samo ispiše plan (dry-run).")
final class FixCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Parameters(paramLabel = "<zona>")
    String zone;

    @Option(names = "--apply", description = "Zaista primeni plan (potpiši, isporuči, upiši reload listu).")
    boolean apply;

    @Option(names = "--lab-copy-secondary", description = "F1 u labu: kopiraj ispravnu zonu na auth2.")
    boolean labCopySecondary;

    @Override
    public Integer call() {
        spec.commandLine().getErr().println(Messages.get("cli.notImplemented", "fix"));
        return Main.EXIT_TOOL_ERROR;
    }
}
