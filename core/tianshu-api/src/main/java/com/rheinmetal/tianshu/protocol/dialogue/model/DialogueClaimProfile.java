package com.rheinmetal.tianshu.protocol.dialogue.model;

import java.util.List;

public record DialogueClaimProfile(
        DialogueClaimMode mode,
        List<DialogueClaimRule> rules
) {
    public static final DialogueClaimProfile DEFAULT_OWNER = new DialogueClaimProfile(DialogueClaimMode.DEFAULT_OWNER, List.of());
    public static final DialogueClaimProfile DISABLED = new DialogueClaimProfile(DialogueClaimMode.DISABLED, List.of());

    public DialogueClaimProfile {
        mode = mode == null ? DialogueClaimMode.RULES : mode;
        rules = rules == null ? List.of() : List.copyOf(rules.stream().filter(rule -> rule != null).toList());
    }

    public static DialogueClaimProfile rules(List<DialogueClaimRule> rules) {
        return new DialogueClaimProfile(DialogueClaimMode.RULES, rules);
    }

    public static DialogueClaimProfile rules(DialogueClaimRule... rules) {
        return rules(List.of(rules));
    }

    public static DialogueClaimProfile defaultOwnerWithRules(List<DialogueClaimRule> rules) {
        return new DialogueClaimProfile(DialogueClaimMode.DEFAULT_OWNER, rules);
    }

    public static DialogueClaimProfile defaultOwnerWithRules(DialogueClaimRule... rules) {
        return defaultOwnerWithRules(List.of(rules));
    }

}
