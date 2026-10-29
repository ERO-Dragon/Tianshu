/*
 * Drives the presence HUD preview: the control panel, the virtual clocks, the 240 ms visual transition and the partner
 * marker's lease. The drawing itself lives in renderer.js; this file only decides what state a frame is in.
 */
(function () {
    'use strict';

    const $ = (id) => document.getElementById(id);
    const clamp = (value, low, high) => Math.min(high, Math.max(low, value));
    const clamp01 = (value) => clamp(value, 0, 1);
    const smootherstep = (value) => value * value * value * (value * (value * 6 - 15) + 10);

    const modelElement = $('presence-hud-model');
    let model = null;
    try {
        model = JSON.parse(modelElement.textContent);
    } catch (failure) {
        $('asset-status').textContent = '模型为空：先运行 exportHudPreviewAssets';
        $('asset-status').classList.add('bad');
        throw failure;
    }

    const constant = (owner, name) => {
        const value = model.constants[owner] ? model.constants[owner][name] : undefined;
        if (value === undefined) {
            throw new Error('model is missing ' + owner + '.' + name);
        }
        return value;
    };

    const DEFAULT_SIZE = constant('PresenceHudSettings', 'DEFAULT_ICON_SIZE_PIXELS');
    const MIN_SIZE = constant('PresenceHudSettings', 'MIN_ICON_SIZE_PIXELS');
    const MAX_SIZE = constant('PresenceHudSettings', 'MAX_ICON_SIZE_PIXELS');
    const DEFAULT_X = constant('PresenceHudLayout', 'DEFAULT_X');
    const XP_TOP_OFFSET = constant('PresenceHudLayout', 'EXPERIENCE_LEVEL_TOP_OFFSET');
    const XP_GAP = constant('PresenceHudLayout', 'EXPERIENCE_LEVEL_GAP');
    const TRANSITION_MILLIS = constant('PresenceIconElementController', 'TRANSITION_MILLIS');
    const ENTER_MILLIS = constant('PresencePartnerIconRenderer', 'ENTER_MILLIS');
    const EXIT_MILLIS = constant('PresencePartnerIconRenderer', 'EXIT_MILLIS');
    const ENTER_MIN_SCALE = constant('PresencePartnerIconRenderer', 'ENTER_MIN_SCALE');
    const ENTER_SPIN = constant('PresencePartnerIconRenderer', 'ENTER_SPIN_RADIANS');
    const EXIT_SPIN = constant('PresencePartnerIconRenderer', 'EXIT_SPIN_RADIANS');
    const RING_GROW_MILLIS = constant('PresencePartnerIconRenderer', 'RING_GROW_MILLIS');

    const SCREENS = {
        '1920x1080@2': { physicalW: 1920, physicalH: 1080, scale: 2 },
        '1920x1080@3': { physicalW: 1920, physicalH: 1080, scale: 3 },
        '2560x1440@2': { physicalW: 2560, physicalH: 1440, scale: 2 },
        '2560x1440@3': { physicalW: 2560, physicalH: 1440, scale: 3 },
        '1280x720@2': { physicalW: 1280, physicalH: 720, scale: 2 },
        '3840x2160@3': { physicalW: 3840, physicalH: 2160, scale: 3 }
    };

    const PRIMARY_CYCLE = ['IDLE', 'LOADING', 'THINKING', 'RESPONDING', 'ERROR', 'RECONNECTING'];

    const state = {
        primary: 'THINKING',
        listening: false,
        backgroundTask: false,
        preset: 'PRESET_ONE',
        hud: true,
        icon: true,
        text: true,
        fallback: true,
        shader: true,
        partnerEnabled: false,
        size: DEFAULT_SIZE,
        posX: DEFAULT_X,
        posY: -1,
        defaultAnchor: true,
        language: 'zh_cn',
        screen: '1920x1080@2',
        partnerIcon: 'star',
        leaseSeconds: 8,
        autoCycle: false,
        paused: false,
        speed: 1,
        zoom: 1
    };

    let renderMs = 0;
    let wallMs = 0;
    let lastFrameTime = 0;
    let autoCycleAt = 0;
    let autoCycleIndex = 0;

    const transition = { from: null, target: null, startedAt: 0 };
    const partner = {
        phase: 'on',
        phaseStart: 0,
        leaseUntil: 0,
        leaseDuration: 8000,
        ringGrowFrom: 0,
        ringGrowAt: -1,
        ringDrawn: 0
    };

    let screen = resolveScreen();

    function resolveScreen() {
        const preset = SCREENS[state.screen];
        return {
            physicalW: preset.physicalW,
            physicalH: preset.physicalH,
            scale: preset.scale,
            logicalW: Math.floor(preset.physicalW / preset.scale),
            logicalH: Math.floor(preset.physicalH / preset.scale)
        };
    }

    const renderer = window.PresenceHudRenderer.create(
        $('fallback-canvas'),
        $('shader-canvas'),
        $('overlay-canvas'),
        model
    );

    function visualState() {
        return model.visualStateOf[state.primary] || 'IDLE';
    }

    function paramsFor(visual, listening) {
        const values = model.visualParameters[visual][listening ? 1 : 0];
        return {
            state: visual,
            listening: listening,
            intensity: values.intensity,
            speed: values.speed,
            convergence: values.convergence,
            pulse: values.pulse,
            loadingBlend: values.loadingBlend
        };
    }

    function currentParams() {
        return paramsFor(visualState(), state.listening);
    }

    function sampleParams(now) {
        if (!transition.target) {
            return currentParams();
        }
        const from = transition.from || transition.target;
        const to = transition.target;
        const progress = clamp01((now - transition.startedAt) / TRANSITION_MILLIS);
        if (progress >= 1) {
            return to;
        }
        const eased = smootherstep(progress);
        return {
            state: to.state,
            listening: eased < 0.5 ? from.listening : to.listening,
            intensity: from.intensity + (to.intensity - from.intensity) * eased,
            speed: from.speed + (to.speed - from.speed) * eased,
            convergence: from.convergence + (to.convergence - from.convergence) * eased,
            pulse: from.pulse + (to.pulse - from.pulse) * eased,
            loadingBlend: from.loadingBlend + (to.loadingBlend - from.loadingBlend) * eased
        };
    }

    function beginTransition(now) {
        const target = currentParams();
        transition.from = sampleParams(now);
        transition.target = target;
        transition.startedAt = now;
    }

    function resolveLayout() {
        const width = Math.max(1, screen.logicalW);
        const height = Math.max(1, screen.logicalH);
        const size = finiteClamp(state.size, MIN_SIZE, MAX_SIZE, DEFAULT_SIZE);
        const x = clamp01(state.posX);
        const defaultAnchor = state.defaultAnchor;
        const y = defaultAnchor ? 0 : clamp01(state.posY);
        const centreX = clamp(x * width, size / 2, width - size / 2);
        const centreY = defaultAnchor
            ? height - XP_TOP_OFFSET - XP_GAP - size / 2
            : clamp(y * height, size / 2, height - size / 2);
        return {
            centerX: centreX,
            centerY: centreY,
            sizePixels: size,
            defaultAnchor: defaultAnchor,
            normalizedX: x,
            normalizedY: y
        };
    }

    function finiteClamp(value, low, high, fallback) {
        if (!Number.isFinite(value)) {
            return fallback;
        }
        return Math.min(high, Math.max(low, value));
    }

    function textKey() {
        return model.textKeyPrefix + (state.listening ? 'listening' : state.primary.toLowerCase());
    }

    function displayText() {
        const language = model.texts[state.language] || {};
        return language[state.listening ? 'listening' : state.primary.toLowerCase()] || '';
    }

    function partnerFrame(now) {
        let alpha = 0;
        let scale = 1;
        let spin = 0;
        if (state.partnerEnabled && partner.phase !== 'off') {
            if (partner.phase === 'in') {
                const progress = clamp01((now - partner.phaseStart) / ENTER_MILLIS);
                alpha = progress;
                scale = ENTER_MIN_SCALE + (1 - ENTER_MIN_SCALE) * progress;
                spin = ENTER_SPIN * (1 - progress);
                if (progress >= 1) {
                    partner.phase = 'on';
                }
            } else if (partner.phase === 'on') {
                alpha = 1;
            } else if (partner.phase === 'out') {
                const progress = clamp01((now - partner.phaseStart) / EXIT_MILLIS);
                alpha = 1 - progress;
                scale = 1 - (1 - ENTER_MIN_SCALE) * progress;
                spin = EXIT_SPIN * progress;
                if (progress >= 1) {
                    partner.phase = 'off';
                }
            }
        }

        let remaining = clamp01((partner.leaseUntil - now) / partner.leaseDuration);
        if (partner.ringGrowAt >= 0) {
            const grown = clamp01((now - partner.ringGrowAt) / RING_GROW_MILLIS);
            partner.ringDrawn = partner.ringGrowFrom + (1 - partner.ringGrowFrom) * grown;
            if (grown >= 1) {
                partner.ringGrowAt = -1;
                partner.ringDrawn = remaining;
            }
        } else {
            partner.ringDrawn = remaining;
        }

        return {
            present: alpha > 0.01,
            alpha: alpha,
            scale: scale,
            spin: spin,
            ringCoverage: state.partnerEnabled && alpha > 0.01 ? partner.ringDrawn : 0,
            iconId: state.partnerIcon,
            remaining: remaining
        };
    }

    function restartLease(now) {
        partner.ringGrowFrom = partner.ringDrawn;
        partner.ringGrowAt = now;
        partner.leaseDuration = Math.max(1, state.leaseSeconds) * 1000;
        partner.leaseUntil = now + partner.leaseDuration;
    }

    function setPrimary(primary) {
        if (state.primary === primary) {
            return;
        }
        const now = renderMs;
        state.primary = primary;
        beginTransition(now);
        syncPrimaryButtons();
    }

    function syncPrimaryButtons() {
        document.querySelectorAll('.state-button').forEach((button) => {
            button.classList.toggle('active', button.dataset.primary === state.primary);
        });
    }

    function frame(now) {
        const delta = Math.min(100, lastFrameTime === 0 ? 16 : now - lastFrameTime);
        lastFrameTime = now;
        if (!state.paused) {
            const scaled = delta * state.speed;
            renderMs += scaled;
            wallMs += scaled;
        }

        if (state.autoCycle && renderMs - autoCycleAt > 2000) {
            autoCycleAt = renderMs;
            autoCycleIndex = (autoCycleIndex + 1) % PRIMARY_CYCLE.length;
            setPrimary(PRIMARY_CYCLE[autoCycleIndex]);
        }

        const visual = sampleParams(renderMs);
        const layout = resolveLayout();
        const display = {
            text: displayText(),
            listening: state.listening,
            backgroundTask: state.backgroundTask,
            primaryState: state.primary
        };
        const currentPartner = partnerFrame(wallMs);

        const frameData = {
            renderSeconds: renderMs / 1000,
            wallMillis: wallMs,
            logicalW: screen.logicalW,
            logicalH: screen.logicalH,
            scale: screen.scale,
            settings: {
                hudEnabled: state.hud,
                iconEnabled: state.icon,
                textEnabled: state.text,
                shaderEnabled: state.shader,
                fallbackEnabled: state.fallback,
                preset: state.preset
            },
            layout: layout,
            visual: visual,
            display: display,
            partner: currentPartner
        };

        renderer.draw(frameData);
        updateReadout(frameData);
        requestAnimationFrame(frame);
    }

    let lastReadout = -1000;

    function updateReadout(frameData) {
        if (renderMs - lastReadout < 90) {
            return;
        }
        lastReadout = renderMs;
        const rows = [
            ['主状态', state.primary],
            ['视觉状态', frameData.visual.state],
            ['监听 / 后台', (state.listening ? '是' : '否') + ' / ' + (state.backgroundTask ? '是' : '否')],
            ['文字键', textKey().replace(model.textKeyPrefix, '…')],
            ['文字', frameData.display.text || '（空 → 面板隐藏）'],
            ['尺寸', frameData.layout.sizePixels.toFixed(1) + ' px'],
            ['中心', frameData.layout.centerX.toFixed(0) + ', ' + frameData.layout.centerY.toFixed(0)],
            ['锚点', frameData.layout.defaultAnchor ? '经验条上方' : '自定义'],
            ['intensity', frameData.visual.intensity.toFixed(3)],
            ['speed', frameData.visual.speed.toFixed(3)],
            ['convergence', frameData.visual.convergence.toFixed(3)],
            ['pulse', frameData.visual.pulse.toFixed(3)],
            ['loadingBlend', frameData.visual.loadingBlend.toFixed(3)],
            ['过渡', transitionProgress().toFixed(2)],
            ['伙伴环', (frameData.partner.ringCoverage * 100).toFixed(0) + '% · 剩余 ' + (frameData.partner.remaining * 100).toFixed(0) + '%'],
            ['渲染时钟', (frameData.renderSeconds).toFixed(2) + ' s']
        ];
        const readout = $('readout');
        readout.textContent = '';
        rows.forEach(([label, value]) => {
            const term = document.createElement('dt');
            term.textContent = label;
            const definition = document.createElement('dd');
            definition.textContent = value;
            readout.append(term, definition);
        });

        const status = renderer.shaderStatus();
        const statusElement = $('shader-status');
        statusElement.textContent = status.text;
        statusElement.classList.toggle('bad', !status.ok);
    }

    function transitionProgress() {
        if (!transition.target) {
            return 1;
        }
        return clamp01((renderMs - transition.startedAt) / TRANSITION_MILLIS);
    }

    // ------------------------------------------------------------------ controls

    document.querySelectorAll('.state-button').forEach((button) => {
        button.addEventListener('click', () => setPrimary(button.dataset.primary));
    });

    $('listening').addEventListener('change', (event) => {
        state.listening = event.target.checked;
        beginTransition(renderMs);
    });

    $('background-task').addEventListener('change', (event) => {
        state.backgroundTask = event.target.checked;
    });

    document.querySelectorAll('input[name="preset"]').forEach((input) => {
        input.addEventListener('change', () => {
            if (input.checked) {
                state.preset = input.value;
            }
        });
    });

    const toggles = {
        'hud-enabled': 'hud',
        'icon-enabled': 'icon',
        'text-enabled': 'text',
        'shader-enabled': 'shader',
        'fallback-enabled': 'fallback'
    };
    Object.entries(toggles).forEach(([id, key]) => {
        $(id).addEventListener('change', (event) => {
            state[key] = event.target.checked;
        });
    });

    $('partner-enabled').addEventListener('change', (event) => {
        state.partnerEnabled = event.target.checked;
        const now = wallMs;
        if (event.target.checked) {
            partner.phase = partner.phase === 'on' ? 'on' : 'in';
            partner.phaseStart = now;
            restartLease(now);
        } else if (partner.phase !== 'off') {
            partner.phase = 'out';
            partner.phaseStart = now;
        }
    });

    $('size').addEventListener('input', (event) => {
        state.size = Number(event.target.value);
        $('size-value').textContent = String(state.size);
        applyZoom();
    });

    $('pos-x').addEventListener('input', (event) => {
        state.posX = Number(event.target.value);
        $('pos-x-value').textContent = state.posX.toFixed(3);
        applyZoom();
    });

    $('default-anchor').addEventListener('change', (event) => {
        state.defaultAnchor = event.target.checked;
        state.posY = state.defaultAnchor ? -1 : Number($('pos-y').value);
        $('pos-y').disabled = state.defaultAnchor;
        $('pos-y-value').textContent = state.defaultAnchor ? '-1.000' : state.posY.toFixed(3);
        applyZoom();
    });

    $('pos-y').addEventListener('input', (event) => {
        state.posY = Number(event.target.value);
        $('pos-y-value').textContent = state.posY.toFixed(3);
        applyZoom();
    });

    $('partner-icon').addEventListener('change', (event) => {
        state.partnerIcon = event.target.value;
    });

    $('lease').addEventListener('input', (event) => {
        state.leaseSeconds = Number(event.target.value);
        $('lease-value').textContent = state.leaseSeconds.toFixed(1);
        restartLease(wallMs);
    });

    $('restart-lease').addEventListener('click', () => restartLease(wallMs));

    $('auto-cycle').addEventListener('change', (event) => {
        state.autoCycle = event.target.checked;
        autoCycleAt = renderMs;
        autoCycleIndex = Math.max(0, PRIMARY_CYCLE.indexOf(state.primary));
    });

    $('replay-transition').addEventListener('click', () => {
        const now = renderMs;
        transition.from = paramsFor('IDLE', false);
        transition.target = currentParams();
        transition.startedAt = now;
    });

    $('language').addEventListener('change', (event) => {
        state.language = event.target.value;
    });

    $('screen').addEventListener('change', (event) => {
        state.screen = event.target.value;
        screen = resolveScreen();
        applyScreen();
    });

    $('zoom').addEventListener('change', (event) => {
        state.zoom = Number(event.target.value);
        applyZoom();
    });

    $('pause').addEventListener('click', () => {
        state.paused = !state.paused;
        $('pause').textContent = state.paused ? '继续' : '暂停';
    });

    $('speed').addEventListener('input', (event) => {
        state.speed = Number(event.target.value);
        $('speed-value').textContent = state.speed.toFixed(2) + '×';
    });

    $('copy-state').addEventListener('click', async () => {
        const layout = resolveLayout();
        const visual = sampleParams(renderMs);
        const payload = {
            primary: state.primary,
            visualState: visual.state,
            listening: state.listening,
            backgroundTask: state.backgroundTask,
            preset: state.preset,
            layout: {
                centerX: layout.centerX,
                centerY: layout.centerY,
                sizePixels: layout.sizePixels,
                defaultAnchor: layout.defaultAnchor
            },
            visual: {
                intensity: visual.intensity,
                speed: visual.speed,
                convergence: visual.convergence,
                pulse: visual.pulse,
                loadingBlend: visual.loadingBlend
            },
            text: displayText(),
            textKey: textKey()
        };
        try {
            await navigator.clipboard.writeText(JSON.stringify(payload, null, 2));
            $('asset-status').textContent = '状态已复制';
        } catch (failure) {
            $('asset-status').textContent = '剪贴板不可用（file:// 限制）';
        }
        $('asset-status').classList.remove('bad');
    });

    function applyScreen() {
        renderer.resize(screen);
        $('screen-label').textContent = screen.physicalW + '×' + screen.physicalH + ' · GUI ' + screen.scale + '× · 逻辑 '
            + screen.logicalW + '×' + screen.logicalH;
        applyZoom();
    }

    function applyZoom() {
        const surface = document.getElementById('screen-surface');
        const layout = resolveLayout();
        surface.style.transformOrigin = ((layout.centerX / Math.max(1, screen.logicalW)) * 100) + '% '
            + ((layout.centerY / Math.max(1, screen.logicalH)) * 100) + '%';
        surface.style.transform = state.zoom === 1 ? 'none' : 'scale(' + state.zoom + ')';
    }

    /** Optional deep link, mainly so a specific state can be screenshotted without clicking the panel. */
    function applyQuery() {
        const query = new URLSearchParams(window.location.search);
        const text = (name) => query.get(name);
        const flag = (name) => text(name) === '1' || text(name) === 'true';
        const number = (name, fallback) => {
            const raw = text(name);
            if (raw === null || raw === '') {
                return fallback;
            }
            const value = Number(raw);
            return Number.isFinite(value) ? value : fallback;
        };
        if (PRIMARY_CYCLE.includes(text('primary'))) {
            state.primary = text('primary');
        }
        if (text('preset') === 'PRESET_ONE' || text('preset') === 'PRESET_TWO') {
            state.preset = text('preset');
        }
        state.listening = flag('listening');
        state.backgroundTask = flag('background');
        if (text('lang') && model.texts[text('lang')]) {
            state.language = text('lang');
        }
        if (SCREENS[text('screen')]) {
            state.screen = text('screen');
            screen = resolveScreen();
        }
        state.size = clamp(number('size', state.size), MIN_SIZE, MAX_SIZE);
        state.posX = flag('customx') ? clamp(number('posx', state.posX), 0, 1) : state.posX;
        state.defaultAnchor = !text('anchor') || flag('anchor');
        state.posY = state.defaultAnchor ? -1 : clamp(number('posy', 0.5), 0, 1);
        state.hud = !text('hud') || flag('hud');
        state.icon = !text('icon') || flag('icon');
        state.text = !text('text') || flag('text');
        state.shader = !text('shader') || flag('shader');
        state.fallback = !text('fallback') || flag('fallback');
        state.partnerEnabled = flag('partner');
        state.partnerIcon = text('partnericon') || state.partnerIcon;
        state.leaseSeconds = clamp(number('lease', state.leaseSeconds), 2, 20);
        state.zoom = clamp(number('zoom', 1), 1, 3);
        state.speed = clamp(number('speed', 1), 0.1, 2);
        state.paused = flag('paused');
        state.autoCycle = flag('cycle');
        const at = number('t', 0);
        if (at > 0) {
            renderMs = at * 1000;
            wallMs = renderMs;
            autoCycleAt = renderMs;
        }
    }

    function syncControls() {
        syncPrimaryButtons();
        $('listening').checked = state.listening;
        $('background-task').checked = state.backgroundTask;
        document.querySelectorAll('input[name="preset"]').forEach((input) => {
            input.checked = input.value === state.preset;
        });
        $('hud-enabled').checked = state.hud;
        $('icon-enabled').checked = state.icon;
        $('text-enabled').checked = state.text;
        $('shader-enabled').checked = state.shader;
        $('fallback-enabled').checked = state.fallback;
        $('partner-enabled').checked = state.partnerEnabled;
        $('size').value = String(state.size);
        $('size-value').textContent = String(state.size);
        $('pos-x').value = String(state.posX);
        $('pos-x-value').textContent = state.posX.toFixed(3);
        $('default-anchor').checked = state.defaultAnchor;
        $('pos-y').disabled = state.defaultAnchor;
        $('pos-y').value = String(state.defaultAnchor ? 0.5 : state.posY);
        $('pos-y-value').textContent = state.defaultAnchor ? '-1.000' : state.posY.toFixed(3);
        $('language').value = state.language;
        $('screen').value = state.screen;
        $('zoom').value = String(state.zoom);
        $('speed').value = String(state.speed);
        $('speed-value').textContent = state.speed.toFixed(2) + '×';
        $('lease').value = String(state.leaseSeconds);
        $('lease-value').textContent = state.leaseSeconds.toFixed(1);
        $('partner-icon').value = state.partnerIcon;
        $('auto-cycle').checked = state.autoCycle;
        $('pause').textContent = state.paused ? '继续' : '暂停';
    }

    // ------------------------------------------------------------------ boot

    applyQuery();
    syncControls();
    applyScreen();
    applyZoom();
    partner.leaseDuration = state.leaseSeconds * 1000;
    partner.leaseUntil = wallMs + partner.leaseDuration;
    partner.phase = state.partnerEnabled ? (state.paused ? 'on' : 'in') : 'off';
    partner.phaseStart = state.paused ? wallMs - ENTER_MILLIS : wallMs;
    partner.ringGrowFrom = 0;
    partner.ringGrowAt = state.paused ? -1 : 0;
    partner.ringDrawn = state.paused ? 1 : 0;
    $('asset-status').textContent = '模型已加载 · ' + model.moduleId;
    transition.target = currentParams();
    transition.from = transition.target;
    transition.startedAt = renderMs;
    requestAnimationFrame(frame);
})();
