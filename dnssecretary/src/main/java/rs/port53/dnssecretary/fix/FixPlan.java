package rs.port53.dnssecretary.fix;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.xbill.DNS.Name;
import rs.port53.dnssecretary.catalog.ErrorId;

/** The actions for one zone, in the order they are applied. Empty = nothing to fix. */
public record FixPlan(Name zone, List<FixAction> actions) {

    public FixPlan {
        actions = List.copyOf(actions);
    }

    public boolean isEmpty() {
        return actions.isEmpty();
    }

    /** At least one action the script does (not only manual steps). */
    public boolean automatic() {
        return actions.stream().anyMatch(FixAction::automatic);
    }

    /** All errors the automatic actions remove. */
    public Set<ErrorId> fixes() {
        Set<ErrorId> out = EnumSet.noneOf(ErrorId.class);
        actions.forEach(a -> out.addAll(a.fixes()));
        return out;
    }

    public Optional<FixAction.ResignZone> resign() {
        return find(FixAction.ResignZone.class);
    }

    public Optional<FixAction.UpdateParentDs> parentDs() {
        return find(FixAction.UpdateParentDs.class);
    }

    public List<FixAction.Manual> manual() {
        return actions.stream().filter(FixAction.Manual.class::isInstance).map(FixAction.Manual.class::cast)
                .toList();
    }

    private <T extends FixAction> Optional<T> find(Class<T> cls) {
        return actions.stream().filter(cls::isInstance).map(cls::cast).findFirst();
    }
}
