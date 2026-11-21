package com.rheinmetal.tianshu.dubhe.settings;

import com.rheinmetal.tianshu.dubhe.settings.DubheSettingsAccess;
import com.rheinmetal.tianshu.client.api.settings.ModuleSettingsContext;
import com.rheinmetal.tianshu.client.api.settings.ModuleSettingsPanel;
import com.rheinmetal.tianshu.client.settings.model.ModuleSettingsCategory;
import com.rheinmetal.tianshu.client.settings.registry.TianshuSettingsRegistry;
import com.rheinmetal.tianshu.client.settings.registry.TianshuSettingsRegistrySource;
import com.rheinmetal.tianshu.client.settings.session.MutableSettingsValue;
import com.rheinmetal.tianshu.client.settings.session.SettingsSaveResult;
import com.rheinmetal.tianshu.client.settings.session.SettingsSaveTransaction;
import com.rheinmetal.tianshu.api.assembly.ClientKeyBindings;
import com.rheinmetal.tianshu.api.assembly.ModuleRuntimeServices;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.dubhe.dialogue.activity.DubheActivityLog;
import com.rheinmetal.tianshu.dubhe.shared.failure.DubheRetryEntry;
import com.rheinmetal.tianshu.dubhe.shared.failure.DubheRetryService;
import com.rheinmetal.tianshu.client.api.settings.SettingsListCard;
import com.rheinmetal.tianshu.client.host.ClientUiHost;
import com.rheinmetal.tianshu.dubhe.DubheModule;
import com.rheinmetal.tianshu.core.runtime.RuntimeRefreshReason;

import com.rheinmetal.tianshu.client.api.text.UiText;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class DubheSettingsRegistrySource implements TianshuSettingsRegistrySource {
    private final ModuleRuntimeServices services;
    private final DubheSettingsAccess config;

    /**
     * 右栏当前显示哪一半：日志还是待办。
     *
     * <p>它不是设置，所以不是 {@code MutableSettingsValue}，也不进 {@code dirty()} 与存档——页面刷新会重建
     * 会话对象，所以这个状态挂在注册源上，刷新之后玩家看到的还是他刚才选的那一栏。</p>
     */
    private volatile boolean showLog = true;

    public DubheSettingsRegistrySource(ModuleRuntimeServices services, DubheSettingsAccess config) {
        this.services = services;
        this.config = config;
    }

    @Override
    public void contribute(TianshuSettingsRegistry registry, ModuleSettingsContext context) {
        if (registry == null || context == null || services == null || config == null) {
            return;
        }
        DubheSettingsSession session = new DubheSettingsSession(config, services);
        context.settingsSessions().registerOrReplace(session);
        registry.registerCategory(ModuleSettingsCategory.builder(DubheModule.MODULE_ID)
                .title(ax("title"))
                .description(ax("description"))
                .order(35)
                .panel((panel, panelContext) -> buildPanel(panel, session))
                .build());
    }

    /**
     * 两栏：左边是玩家能调的设置，右边是他能看到的结果。
     *
     * <p>这样分是因为两类东西回答的问题不同："我要天枢怎么说话"和"天枢刚才干了什么"。混在一列里，
     * 玩家要往下滚很久才能看见失败；分成两栏，右栏切一下就到现在最该看的那一半。</p>
     */
    private void buildPanel(ModuleSettingsPanel panel, DubheSettingsSession session) {
        panel.enable("dubhe.enabled", ax("enabled"), session.enabled)
                .columns("dubhe.body", 0.62, 0.38, columns -> columns
                        .column(0, left -> buildSettingsColumn(left, session))
                        .column(1, this::buildSideColumn));
    }

    private void buildSettingsColumn(ModuleSettingsPanel panel, DubheSettingsSession session) {
        panel.options("dubhe.identity", ax("section.identity"), session.enabled::get, options -> options
                        .text("dubhe.identity.wake_word", ax("option.wake_word"), session.wakeWord, session.enabled::get))
                .toggles("dubhe.reply", ax("section.reply"), session.enabled::get, toggles -> toggles
                        .toggle("dubhe.reply.speech", ax("option.reply_speech"), session.replySpeechEnabled, session.enabled::get))
                .toggles("dubhe.behavior", ax("section.behavior"), session.enabled::get, toggles -> toggles
                        .toggle("dubhe.behavior.thinking", ax("option.chat_thinking"), session.chatThinkingEnabled, session.enabled::get)
                        .toggle("dubhe.behavior.interrupt", ax("option.allow_interruption"), session.allowInterruption, session.enabled::get))
                .toggles("dubhe.dialogue.input", ax("section.input"), session.enabled::get, toggles -> toggles
                        .toggle("dubhe.dialogue.input.text", ax("option.text_input"), session.textInputEnabled, session.enabled::get))
                .actions("dubhe.dialogue.input.key", ax("section.input_key"), session.enabled::get, actions -> actions
                        .button("dubhe.dialogue.input.rebind", ax("action.rebind"), () -> rebind(KEY_TEXT_INPUT_KEY), session.enabled::get));
    }

    /** 右栏：日志，或者切过去看待办（单次记忆维护重试）。 */
    private void buildSideColumn(ModuleSettingsPanel panel) {
        panel.status("dubhe.side.state", ax("section.side"), status -> status
                        .row("dubhe.side.current", ax("row.side_current"), () -> ax(showLog ? "side.log" : "side.retry")))
                .actions("dubhe.side.switch", ax("section.side_switch"), actions -> actions
                        .button("dubhe.side.show_log", ax("action.show_log"), () -> switchSide(true))
                        .button("dubhe.side.show_retry", ax("action.show_retry"), () -> switchSide(false)));
        if (showLog) {
            panel.<DubheActivityLog.Entry>list("dubhe.log", ax("section.log"), list -> list
                    .items(this::logEntries)
                    .card(this::logCard)
                    .emptyText(ax("log.empty")));
            return;
        }
        panel.<DubheRetryEntry>list("dubhe.retry", ax("section.retry"), list -> list
                .items(this::retryEntries)
                .card(this::retryCard)
                .itemActions((entry, actions) -> actions
                        .button("dubhe.retry.retry", ax("action.retry"), item -> retryPending(), this::canRetry)
                        .button("dubhe.retry.dismiss", ax("action.dismiss"), item -> dismissPending()))
                .emptyText(ax("retry.empty")));
    }

    private void switchSide(boolean log) {
        this.showLog = log;
        refreshSettingsScreen();
    }

    private List<DubheActivityLog.Entry> logEntries() {
        DubheActivityLog log = activityLog();
        return log == null ? List.of() : log.entries();
    }

    private SettingsListCard logCard(DubheActivityLog.Entry entry) {
        if (entry == null) {
            return SettingsListCard.text(UiText.literal(""));
        }
        List<UiText> details = new ArrayList<>();
        if (!entry.detail().isBlank()) {
            details.add(ax("log.detail", UiText.literal(entry.detail())));
        }
        return new SettingsListCard(UiText.key("tianshu.gui.dubhe.log." + entry.code()), UiText.literal(""), details, List.of());
    }

    private List<DubheRetryEntry> retryEntries() {
        DubheRetryService service = retryService();
        return service == null ? List.of() : service.entries();
    }

    private SettingsListCard retryCard(DubheRetryEntry entry) {
        if (entry == null) {
            return SettingsListCard.text(UiText.literal(""));
        }
        List<UiText> details = new ArrayList<>();
        details.add(ax("retry.detail.reason", UiText.key(entry.cause().messageKey())));
        if (!entry.reasonCode().isBlank()) {
            details.add(ax("retry.detail.code", UiText.literal(entry.reasonCode())));
        }
        if (entry.attempts() > 0) {
            details.add(ax("retry.detail.attempts", UiText.literal(Integer.toString(entry.attempts()))));
        }
        return new SettingsListCard(
                ax("retry.title"),
                UiText.key(entry.cause().messageKey()),
                details,
                List.of(ax(entry.retryable() ? "retry.badge.retryable" : "retry.badge.final"))
        );
    }

    private boolean canRetry(DubheRetryEntry entry) {
        DubheRetryService service = retryService();
        return service != null && service.canRetry(entry);
    }

    private void retryPending() {
        DubheRetryService service = retryService();
        if (service != null) {
            service.retry();
            refreshSettingsScreen();
        }
    }

    private void dismissPending() {
        DubheRetryService service = retryService();
        if (service != null) {
            service.dismiss();
            refreshSettingsScreen();
        }
    }

    /** 右栏换了一份内容，页面要重画：否则玩家点了按钮却看不出发生了什么。 */
    private void refreshSettingsScreen() {
        TianshuRuntimePorts.find(ClientUiHost.class).ifPresent(ClientUiHost::requestSettingsRefresh);
    }

    /**
     * 失败账本的服务面。模块没在跑（未进世界、已停用）时它就是空的，
     * 此时右栏显示"没有待处理"，而不是假装列表被清空了。
     */
    private DubheRetryService retryService() {
        return services == null ? null : services.findService(DubheRetryService.class).orElse(null);
    }

    private DubheActivityLog activityLog() {
        return services == null ? null : services.findService(DubheActivityLog.class).orElse(null);
    }

    /** {@code key.tianshu.text_input} is registered by the platform; the module only asks for its screen. */
    private static final String KEY_TEXT_INPUT_KEY = "key.tianshu.text_input";

    private static void rebind(String keyMappingName) {
        TianshuRuntimePorts.find(ClientKeyBindings.class)
                .ifPresent(bindings -> bindings.openRebindScreen(keyMappingName));
    }

    private static UiText ax(String key, Object... args) {
        return UiText.key("tianshu.gui.dubhe." + key, args);
    }

    private static final class DubheSettingsSession implements com.rheinmetal.tianshu.client.settings.session.ModuleSettingsSession {
        private final DubheSettingsAccess config;
        private final ModuleRuntimeServices services;
        private final MutableSettingsValue<Boolean> enabled;
        private final MutableSettingsValue<String> wakeWord;
        private final MutableSettingsValue<Boolean> replySpeechEnabled;
        private final MutableSettingsValue<Boolean> chatThinkingEnabled;
        private final MutableSettingsValue<Boolean> allowInterruption;
        private final MutableSettingsValue<Boolean> textInputEnabled;

        private DubheSettingsSession(DubheSettingsAccess config, ModuleRuntimeServices services) {
            this.config = Objects.requireNonNull(config, "config");
            this.services = Objects.requireNonNull(services, "services");
            this.enabled = new MutableSettingsValue<>(config::assistantEnabled, config::setAxEnabled);
            this.wakeWord = new MutableSettingsValue<>(config::wakeWord, config::setAxWakeWord);
            this.replySpeechEnabled = new MutableSettingsValue<>(config::isAxReplySpeechEnabled, config::setAxReplySpeechEnabled);
            this.chatThinkingEnabled = new MutableSettingsValue<>(config::chatThinkingEnabled, config::setAxChatThinkingEnabled);
            this.allowInterruption = new MutableSettingsValue<>(config::allowInterruption, config::setAxAllowInterruption);
            this.textInputEnabled = new MutableSettingsValue<>(config::textInputEnabled, config::setAxTextInputEnabled);
        }

        @Override
        public String moduleId() {
            return DubheModule.MODULE_ID;
        }

        @Override
        public boolean dirty() {
            return enabled.dirty()
                    || wakeWord.dirty()
                    || replySpeechEnabled.dirty()
                    || chatThinkingEnabled.dirty()
                    || allowInterruption.dirty()
                    || textInputEnabled.dirty();
        }

        @Override
        public SettingsSaveResult save() {
            boolean changed = dirty();
            if (!changed) {
                return SettingsSaveResult.unchanged(ax("message.saved"));
            }
            boolean runtimeRegistrationChanged = enabled.dirty() || wakeWord.dirty() || textInputEnabled.dirty();
            new SettingsSaveTransaction(enabled, wakeWord, replySpeechEnabled, chatThinkingEnabled, allowInterruption,
                    textInputEnabled)
                    .commit(config::save);
            if (runtimeRegistrationChanged) {
                services.requestRefresh(RuntimeRefreshReason.RESOURCE_CHANGED);
            }
            return SettingsSaveResult.success(ax("message.saved"), changed, false, false);
        }

        @Override
        public void reset() {
            enabled.reset();
            wakeWord.reset();
            replySpeechEnabled.reset();
            chatThinkingEnabled.reset();
            allowInterruption.reset();
            textInputEnabled.reset();
        }
    }
}