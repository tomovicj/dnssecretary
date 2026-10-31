package rs.port53.dnssecretary.cli;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import org.xbill.DNS.Name;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import rs.port53.dnssecretary.config.AppConfig;
import rs.port53.dnssecretary.report.Messages;
import rs.port53.dnssecretary.web.WebApp;

/** serve: the web app for DNS hosting admins (REST + SSE, and webapp/dist if built). Runs until stopped. */
@Command(name = "serve", mixinStandardHelpOptions = true,
        description = "Run the web app: a live list of broken zones with fix scripts tried in a sandbox.")
final class ServeCommand implements Callable<Integer> {

    @ParentCommand
    Main main;

    @Spec
    CommandSpec spec;

    @Option(names = "--port", defaultValue = "8080", description = "HTTP port (default: ${DEFAULT-VALUE}).")
    int port;

    @Option(names = "--scan-interval", defaultValue = "20", paramLabel = "<seconds>",
            description = "Seconds between two scans of every zone (default: ${DEFAULT-VALUE}).")
    int scanInterval;

    @Option(names = "--zone", paramLabel = "<zone>",
            description = "A zone to watch (repeatable). Default: good.test and every catalogue lab zone.")
    List<String> zones;

    @Override
    public Integer call() throws Exception {
        AppConfig config = main.config();
        if (config.dns() == null || config.fix() == null) {
            spec.commandLine().getErr().println(Messages.get(config.dns() == null ? "cli.noConfig" : "cli.noFixConfig"));
            return Main.EXIT_TOOL_ERROR;
        }
        List<Name> watched = zones == null || zones.isEmpty() ? WebApp.labZones(main.catalog())
                : zones.stream().map(Main::zoneName).toList();
        WebApp app = WebApp.forLab(config, main.catalog(), Path.of(config.fix().labDir()), watched, Clock.systemUTC());
        int p = app.start(port);
        spec.commandLine().getOut().println(Messages.get("web.listening", p, watched.size(), scanInterval));
        spec.commandLine().getOut().flush();
        app.scanEvery(Duration.ofSeconds(scanInterval));
        Thread.currentThread().join();   // until the container stops
        return 0;
    }
}
