package em.altitude.game.scene.player;

import em.altitude.game.console.AltitudeConsoleCommand;
import em.altitude.game.console.AltitudeConsoleCommandEnumerationParameter;
import em.altitude.game.console.AltitudeConsoleCommandListener;
import em.altitude.game.console.AltitudeConsoleCommandParameter;
import em.altitude.game.ui.UIToolkit;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The /opt console command for {@link ClientOptions}:
 * <pre>
 * /opt                 the build ID, then every option and its value
 * /opt NAME            the option's value and help
 * /opt NAME VALUE      set and save it
 * /opt perf            the latest perf line (see PerfMonitor)
 * </pre>
 * Hook: register at the start of AltitudeConsoleCommandManager.createCommands, which adds it to the client commands
 * like the stock option commands.
 */
public final class OptCommand extends AltitudeConsoleCommand {
    /** /opt perf prints the latest perf line. */
    static final String PERF = "perf";

    private final AltitudeConsoleCommandParameter name;
    /** Each option's own values, so /opt framepacing completes on|off and not another option's values. */
    private final Map<String, AltitudeConsoleCommandParameter> valueByOption = new HashMap<>();

    OptCommand() {
        super("opt");
        String[] names = Stream.concat(ClientOptions.ALL.stream().map(ClientOptions.Option::name), Stream.of(PERF)).toArray(String[]::new);
        name = new AltitudeConsoleCommandEnumerationParameter<String>("name", "client option", names, names);
        for (ClientOptions.Option o : ClientOptions.ALL) {
            String[] values = o.values().toArray(String[]::new);
            valueByOption.put(o.name(), new AltitudeConsoleCommandEnumerationParameter<String>("value", "new value", values, values));
        }
        setParameters(name);
    }

    /** The stock console asks with the words typed so far (completion and usage): the value list follows the option. */
    @Override
    public AltitudeConsoleCommandParameter[] getParameters(String[] args) {
        ClientOptions.Option o = args == null || args.length == 0 ? null : ClientOptions.option(args[0]);
        return o == null ? new AltitudeConsoleCommandParameter[] {name} : new AltitudeConsoleCommandParameter[] {name, valueByOption.get(o.name())};
    }

    /** Hook: start of AltitudeConsoleCommandManager.createCommands, with its client command list. */
    public static void register(List<AltitudeConsoleCommand> clientCommands) {
        clientCommands.add(new OptCommand());
    }

    /** Zero, one or two arguments are all valid; executeInternal explains anything else. */
    @Override
    protected boolean parametersValid(AltitudeConsoleCommandListener listener, String... args) {
        return true;
    }

    @Override
    protected void executeInternal(AltitudeConsoleCommandListener listener, String... args) {
        if (args == null || args.length == 0) {
            for (String line : ClientOptions.summary()) print(listener, line, false);
            print(listener, "/opt NAME for help, /opt NAME VALUE to change", false);
            return;
        }
        if (args.length == 1 && PERF.equalsIgnoreCase(args[0])) {
            PerfStats.Sample s = PerfMonitor.last();
            print(listener, s == null ? "No measurements yet" : s.line(), false);
            return;
        }
        ClientOptions.Option o = ClientOptions.option(args[0]);
        if (o == null || args.length > 2) {
            print(listener, "Usage: /opt [NAME [VALUE]]. Options: " + String.join(", ", ClientOptions.ALL.stream().map(ClientOptions.Option::name).toList()), true);
            return;
        }
        if (args.length == 2) {
            try {
                if (!ClientOptions.set(o.name(), args[1])) {
                    print(listener, o.name() + ": value must be one of " + String.join(", ", o.values()), true);
                    return;
                }
            } catch (IOException e) {
                print(listener, o.name() + " changed, but not saved: " + e.getMessage(), true);
            }
        }
        print(listener, o.name() + " = " + ClientOptions.get(o.name()) + "  (" + String.join("|", o.values()) + ", default " + o.defaultValue() + ")", false);
        if (args.length == 1) print(listener, o.help(), false);
    }

    static void print(AltitudeConsoleCommandListener listener, String text, boolean error) {
        String color = error ? listener.getErrorColorEncoded() : listener.getDefaultColorEncoded();
        listener.onExecuteResult(color + UIToolkit.getTextEncoder().encode(text), false);
    }

    /** No single value: /opt prints its own changes. */
    @Override
    public String getDefaultValue() { return null; }

    @Override
    public String getCurrentValue() { return null; }
}
