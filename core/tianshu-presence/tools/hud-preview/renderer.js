/*
 * The presence HUD, ported to a 2D canvas plus a WebGL layer.
 *
 * The icon is drawn by the real .fsh sources, copied into the page by :tianshu-presence:exportHudPreviewAssets; there
 * is no Java fallback shape underneath. The remaining 2D drawing is the mock game HUD, the status panel and the
 * partner marker (a texture plus its lease ring). Everything is drawn in logical GUI pixels with the same rounding the
 * platform surface uses; the canvas backing store is the physical size, so a GUI scale of 2 behaves like a 2x
 * Minecraft window.
 *
 * Values that mirror a Java constant are read from the generated model block, not typed here. The only things kept in
 * this file are the method-level maths of the renderers, which a constant export cannot capture.
 */
(function () {
    'use strict';

    const clamp = (value, low, high) => Math.min(high, Math.max(low, value));
    const clamp01 = (value) => clamp(value, 0, 1);
    function argb(value) {
        const color = value >>> 0;
        return {
            a: (color >>> 24) & 0xFF,
            r: (color >>> 16) & 0xFF,
            g: (color >>> 8) & 0xFF,
            b: color & 0xFF
        };
    }

    function rgb(value) {
        const color = argb(value);
        return 'rgb(' + color.r + ', ' + color.g + ', ' + color.b + ')';
    }

    function shadowOf(value) {
        const color = argb(value);
        return {
            r: Math.round(color.r * 0.25),
            g: Math.round(color.g * 0.25),
            b: Math.round(color.b * 0.25),
            a: color.a
        };
    }

    const FONT_STACK = '"Microsoft YaHei", "PingFang SC", "Noto Sans SC", system-ui, sans-serif';
    const TEXT_LINE_HEIGHT = 9;

    function constant(model, owner, name) {
        const value = model.constants && model.constants[owner] ? model.constants[owner][name] : undefined;
        if (value === undefined) {
            throw new Error('generated model is missing ' + owner + '.' + name);
        }
        return value;
    }

    function sourceText(id) {
        const element = document.getElementById(id);
        return element ? element.textContent : '';
    }

    // ------------------------------------------------------------------ shader layer

    function createShaderLayer(canvas, model) {
        let gl = null;
        let failed = '';
        let size = null;
        const programs = new Map();
        const shaders = {
            vertex: sourceText('shader-presence-icon-vsh'),
            presetOne: sourceText('shader-presence-preset-one-fsh'),
            presetTwo: sourceText('shader-presence-preset-two-fsh')
        };

        function adapt(source) {
            if (!source || !source.trim()) {
                return '';
            }
            // The page wraps shader text in a <script> block, so the browser hands it a leading newline. GLSL ES
            // requires "#version" to be the very first token, so trim before replacing it.
            const trimmed = source.replace(/^\s+/, '');
            return trimmed.replace('#version 150', '#version 300 es\nprecision highp float;');
        }

        function applySize() {
            if (!gl || !size) {
                return;
            }
            canvas.width = size.physicalW;
            canvas.height = size.physicalH;
            gl.viewport(0, 0, size.physicalW, size.physicalH);
        }

        function init() {
            gl = canvas.getContext('webgl2', {
                alpha: true,
                premultipliedAlpha: false,
                antialias: false,
                depth: false,
                stencil: false
            });
            if (!gl) {
                failed = '浏览器没有 WebGL2 上下文';
                return;
            }
            gl.enable(gl.BLEND);
            gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
            gl.disable(gl.DEPTH_TEST);
            applySize();
        }

        function compile(type, source, label) {
            if (!source) {
                throw new Error('missing shader source: ' + label);
            }
            const shader = gl.createShader(type);
            gl.shaderSource(shader, adapt(source));
            gl.compileShader(shader);
            if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
                const log = gl.getShaderInfoLog(shader);
                gl.deleteShader(shader);
                throw new Error(label + ': ' + log);
            }
            return shader;
        }

        function buildProgram(fragmentSource, label) {
            const vertex = compile(gl.VERTEX_SHADER, shaders.vertex, 'presence_icon.vsh');
            const fragment = compile(gl.FRAGMENT_SHADER, fragmentSource, label);
            const program = gl.createProgram();
            gl.attachShader(program, vertex);
            gl.attachShader(program, fragment);
            gl.linkProgram(program);
            gl.deleteShader(vertex);
            gl.deleteShader(fragment);
            if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
                const log = gl.getProgramInfoLog(program);
                gl.deleteProgram(program);
                throw new Error(label + ' link: ' + log);
            }
            const uniforms = {};
            ['ProjMat', 'ModelViewMat', 'Time', 'Intensity', 'Speed', 'Convergence', 'Pulse', 'Opacity', 'Morph', 'State', 'Task']
                .forEach((name) => {
                    uniforms[name] = gl.getUniformLocation(program, name);
                });
            return {
                program,
                uniforms,
                position: gl.getAttribLocation(program, 'Position'),
                uv: gl.getAttribLocation(program, 'UV0')
            };
        }

        function programFor(key) {
            if (!gl) {
                return null;
            }
            if (programs.has(key)) {
                return programs.get(key);
            }
            try {
                const fragments = {
                    presetOne: shaders.presetOne,
                    presetTwo: shaders.presetTwo
                };
                const labels = {
                    presetOne: 'presence_preset_one.fsh',
                    presetTwo: 'presence_preset_two.fsh'
                };
                const built = buildProgram(fragments[key], labels[key]);
                programs.set(key, built);
                return built;
            } catch (failure) {
                failed = String(failure && failure.message ? failure.message : failure);
                programs.set(key, null);
                return null;
            }
        }

        function resize(next) {
            size = next;
            applySize();
        }

        function clear() {
            if (!gl) {
                return;
            }
            gl.clearColor(0, 0, 0, 0);
            gl.clear(gl.COLOR_BUFFER_BIT);
        }

        function ortho(width, height) {
            return new Float32Array([
                2 / width, 0, 0, 0,
                0, -2 / height, 0, 0,
                0, 0, 1, 0,
                -1, 1, 0, 1
            ]);
        }

        function drawLayer(key, frame, state, opacity, morph) {
            const built = programFor(key);
            if (!built) {
                return;
            }
            const scale = frame.scale;
            const left = (frame.layout.centerX - frame.layout.sizePixels / 2) * scale;
            const top = (frame.layout.centerY - frame.layout.sizePixels / 2) * scale;
            const right = (frame.layout.centerX + frame.layout.sizePixels / 2) * scale;
            const bottom = (frame.layout.centerY + frame.layout.sizePixels / 2) * scale;
            const vertices = new Float32Array([
                left, bottom, 0, 0, 1,
                right, bottom, 0, 1, 1,
                right, top, 0, 1, 0,
                left, bottom, 0, 0, 1,
                right, top, 0, 1, 0,
                left, top, 0, 0, 0
            ]);
            const buffer = gl.createBuffer();
            gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
            gl.bufferData(gl.ARRAY_BUFFER, vertices, gl.DYNAMIC_DRAW);
            gl.useProgram(built.program);
            if (built.position >= 0) {
                gl.enableVertexAttribArray(built.position);
                gl.vertexAttribPointer(built.position, 3, gl.FLOAT, false, 20, 0);
            }
            if (built.uv >= 0) {
                gl.enableVertexAttribArray(built.uv);
                gl.vertexAttribPointer(built.uv, 2, gl.FLOAT, false, 20, 12);
            }
            gl.uniformMatrix4fv(built.uniforms.ProjMat, false, ortho(canvas.width, canvas.height));
            gl.uniformMatrix4fv(built.uniforms.ModelViewMat, false, new Float32Array([
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                0, 0, 0, 1
            ]));
            gl.uniform1f(built.uniforms.Time, frame.renderSeconds);
            gl.uniform1f(built.uniforms.Intensity, frame.visual.intensity);
            gl.uniform1f(built.uniforms.Speed, frame.visual.speed);
            gl.uniform1f(built.uniforms.Convergence, frame.visual.convergence);
            gl.uniform1f(built.uniforms.Pulse, frame.visual.pulse);
            gl.uniform1f(built.uniforms.Opacity, opacity);
            gl.uniform1f(built.uniforms.Morph, morph);
            gl.uniform1f(built.uniforms.State, model.visualStates.indexOf(state === 'LISTENING' ? 'IDLE' : state));
            gl.uniform1f(built.uniforms.Task, frame.display.backgroundTask ? 1.0 : 0.0);
            gl.drawArrays(gl.TRIANGLES, 0, 6);
            gl.deleteBuffer(buffer);
        }

        function draw(frame) {
            if (!gl) {
                init();
            }
            if (!gl) {
                return;
            }
            clear();
            if (!frame.settings.hudEnabled || !frame.settings.iconEnabled) {
                return;
            }
            // The selected preset program owns the entire lifecycle; loadingBlend is its one handoff parameter.
            const presetKey = frame.settings.preset === 'PRESET_TWO' ? 'presetTwo' : 'presetOne';
            drawLayer(presetKey, frame, frame.visual.state, 1, frame.visual.loadingBlend);
        }

        function status() {
            if (failed) {
                return { ok: false, text: failed };
            }
            if (!gl) {
                return { ok: false, text: '未初始化' };
            }
            const compiled = Array.from(programs.entries())
                .filter(([, value]) => value)
                .map(([key]) => key);
            if (compiled.length === 0) {
                return { ok: false, text: '未编译' };
            }
            return { ok: true, text: '已编译：' + compiled.join(', ') };
        }

        return { draw, resize, clear, status };
    }

    // ------------------------------------------------------------------ renderer

    function createRenderer(fallbackCanvas, shaderCanvas, overlayCanvas, model) {
        const ctx = fallbackCanvas.getContext('2d');
        const overlay = overlayCanvas.getContext('2d');
        const shader = createShaderLayer(shaderCanvas, model);
        let screen = { physicalW: 1920, physicalH: 1080, scale: 2, logicalW: 960, logicalH: 540 };

        const RING_SEGMENTS = constant(model, 'PresenceHudShapes', 'RING_SEGMENTS');
        const RING_SEGMENT_LIMIT = constant(model, 'PresenceHudShapes', 'RING_SEGMENT_LIMIT');
        const PANEL_BACKGROUND = constant(model, 'PresenceStatusTextElementRenderer', 'PANEL_BACKGROUND');
        const TEXT_COLOR = constant(model, 'PresenceStatusTextElementRenderer', 'TEXT_COLOR');
        const ACTIVE_ACCENT = constant(model, 'PresenceStatusTextElementRenderer', 'ACTIVE_ACCENT');
        const MIN_PANEL_WIDTH = constant(model, 'PresenceStatusTextElementRenderer', 'MIN_PANEL_WIDTH');
        const PANEL_HORIZONTAL_PADDING = constant(model, 'PresenceStatusTextElementRenderer', 'PANEL_HORIZONTAL_PADDING');
        const PANEL_LEFT = constant(model, 'PresenceStatusTextElementRenderer', 'PANEL_LEFT');
        const PANEL_BOTTOM_MARGIN = constant(model, 'PresenceStatusTextElementRenderer', 'PANEL_BOTTOM_MARGIN');
        const ICON_SCALE = constant(model, 'PresencePartnerIconRenderer', 'ICON_SCALE');
        const RING_RADIUS_SCALE = constant(model, 'PresencePartnerIconRenderer', 'RING_RADIUS_SCALE');
        const RING_THICKNESS_SCALE = constant(model, 'PresencePartnerIconRenderer', 'RING_THICKNESS_SCALE');
        const RING_COLOR = constant(model, 'PresencePartnerIconRenderer', 'RING_COLOR');
        const RING_OPACITY = constant(model, 'PresencePartnerIconRenderer', 'RING_OPACITY');
        const START_ANGLE = constant(model, 'PresencePartnerIconRenderer', 'START_ANGLE');
        const WOBBLE_AMPLITUDE_RADIANS = constant(model, 'PresencePartnerIconRenderer', 'WOBBLE_AMPLITUDE_RADIANS');
        const WOBBLE_PERIOD_MILLIS = constant(model, 'PresencePartnerIconRenderer', 'WOBBLE_PERIOD_MILLIS');

        function resize(next) {
            screen = next;
            fallbackCanvas.width = next.physicalW;
            fallbackCanvas.height = next.physicalH;
            overlayCanvas.width = next.physicalW;
            overlayCanvas.height = next.physicalH;
            shader.resize(next);
        }

        // Two 2D layers sandwich the shader: the fallback core is drawn below it, while the wave ring, the
        // background-task mark, the partner marker and the status panel are drawn above it — the same order the Java
        // renderers run in. The primitives therefore take the context they paint into.
        function paint(context, left, top, right, bottom, color) {
            const x0 = Math.round(left);
            const y0 = Math.round(top);
            const x1 = Math.round(right);
            const y1 = Math.round(bottom);
            if (x1 <= x0 || y1 <= y0) {
                return;
            }
            const alpha = (color >>> 24) & 0xFF;
            if (alpha === 0) {
                return;
            }
            context.globalAlpha = alpha / 255;
            context.fillStyle = rgb(color);
            context.fillRect(x0, y0, x1 - x0, y1 - y0);
            context.globalAlpha = 1;
        }

        function fill(left, top, right, bottom, color) {
            paint(ctx, left, top, right, bottom, color);
        }

        function ringOn(context, centreX, centreY, radius, thickness, color, opacity, rotation, coverage) {
            const halfThickness = Math.max(1, thickness / 2);
            const fullSegments = Math.min(RING_SEGMENT_LIMIT, Math.max(RING_SEGMENTS, Math.round(Math.PI * 2 * radius)));
            const segments = Math.max(1, Math.round(fullSegments * clamp01(coverage)));
            const step = (Math.PI * 2 * Math.max(0.001, radius)) / fullSegments;
            const overlap = Math.max(1, (halfThickness * 2 + 1) / Math.max(0.001, step));
            const smoothedAlpha = clamp(Math.round(opacity * ((color >>> 24) & 0xFF) / overlap), 0, 255);
            if (smoothedAlpha <= 0) {
                return;
            }
            const argbColor = ((smoothedAlpha << 24) | (color & 0x00FFFFFF)) >>> 0;
            for (let index = 0; index < segments; index++) {
                const angle = rotation + (index * Math.PI * 2) / fullSegments;
                const x = centreX + Math.cos(angle) * radius;
                const y = centreY + Math.sin(angle) * radius;
                paint(context, x - halfThickness, y - halfThickness, x + halfThickness + 1,
                    y + halfThickness + 1, argbColor);
            }
        }

        function drawBackground(width, height) {
            const gradient = ctx.createLinearGradient(0, 0, 0, height);
            gradient.addColorStop(0, '#1a2431');
            gradient.addColorStop(0.55, '#111922');
            gradient.addColorStop(1, '#080c11');
            ctx.fillStyle = gradient;
            ctx.fillRect(0, 0, width, height);

            const light = ctx.createRadialGradient(width * 0.5, height * 0.42, 10, width * 0.5, height * 0.42, width * 0.6);
            light.addColorStop(0, 'rgba(120, 178, 214, 0.10)');
            light.addColorStop(1, 'rgba(0, 0, 0, 0)');
            ctx.fillStyle = light;
            ctx.fillRect(0, 0, width, height);

            ctx.fillStyle = 'rgba(255, 255, 255, 0.025)';
            for (let y = Math.round(height * 0.72); y < height; y += 16) {
                ctx.fillRect(0, y, width, 1);
            }
            ctx.fillStyle = 'rgba(0, 0, 0, 0.28)';
            ctx.fillRect(0, height - 60, width, 60);
        }

        function drawHotbar(width, height) {
            const slot = 20;
            const barWidth = slot * 9;
            const left = Math.round((width - barWidth) / 2);
            const top = height - 22;
            fill(left - 1, top - 1, left + barWidth + 1, top + 23, 0xC00C1116);
            for (let index = 0; index < 9; index++) {
                const x = left + index * slot;
                fill(x + 1, top + 1, x + slot - 1, top + 21, 0x66000000);
                fill(x + 1, top + 1, x + slot - 1, top + 2, 0x33FFFFFF);
                fill(x + 1, top + 20, x + slot - 1, top + 21, 0x55000000);
                if (index === 0) {
                    fill(x, top, x + slot, top + 1, 0xFFE8F6FF);
                    fill(x, top + 21, x + slot, top + 22, 0xFFE8F6FF);
                    fill(x, top, x + 1, top + 22, 0xFFE8F6FF);
                    fill(x + slot - 1, top, x + slot, top + 22, 0xFFE8F6FF);
                }
            }
            // A placeholder item in the selected slot so the bar reads as an inventory, not an empty box.
            const itemLeft = left + 6;
            const itemTop = top + 6;
            fill(itemLeft, itemTop + 2, itemLeft + 2, itemTop + 12, 0xFFB98A4A);
            fill(itemLeft + 1, itemTop + 6, itemLeft + 9, itemTop + 9, 0xFF8FA8C8);
            fill(itemLeft + 7, itemTop + 3, itemLeft + 11, itemTop + 8, 0xFFD9E7F5);
        }

        function drawExperience(width, height) {
            const barWidth = 182;
            const left = Math.round((width - barWidth) / 2);
            const top = height - 29;
            fill(left, top, left + barWidth, top + 5, 0xB0080C10);
            fill(left, top, left + Math.round(barWidth * 0.62), top + 5, 0xFF7FE03A);
            fill(left, top, left + barWidth, top + 1, 0x33000000);
            fill(left, top + 4, left + barWidth, top + 5, 0x44000000);
            drawText('17', width / 2, height - 32, 0xFF80FF20, 'center');
        }

        function drawHearts(width, height) {
            const heartY = height - 39;
            const left = Math.round((width - 182) / 2);
            const fullHeart = [
                '01100110',
                '11111111',
                '11111111',
                '01111110',
                '00111100',
                '00011000'
            ];
            for (let index = 0; index < 10; index++) {
                const x = left + index * 8;
                const rows = fullHeart;
                for (let row = 0; row < rows.length; row++) {
                    for (let column = 0; column < rows[row].length; column++) {
                        if (rows[row][column] === '1') {
                            fill(x + column, heartY + row, x + column + 1, heartY + row + 1, 0xFFD6484A);
                            if (row < 2) {
                                fill(x + column, heartY + row, x + column + 1, heartY + row + 1, 0xFFF06A6C);
                            }
                        }
                    }
                }
            }
            // Hunger on the right side, mirroring the heart row.
            for (let index = 0; index < 10; index++) {
                const x = left + 182 - 8 - index * 8;
                fill(x + 2, heartY + 1, x + 6, heartY + 5, 0xFFC0843C);
                fill(x + 1, heartY + 4, x + 3, heartY + 8, 0xFFE0A65A);
                fill(x + 5, heartY + 4, x + 7, heartY + 7, 0xFFE0A65A);
            }
        }

        function drawCrosshair(width, height) {
            const centreX = Math.round(width / 2);
            const centreY = Math.round(height / 2);
            fill(centreX - 5, centreY, centreX + 5, centreY + 1, 0xCCEAEAEA);
            fill(centreX, centreY - 5, centreX + 1, centreY + 5, 0xCCEAEAEA);
        }

        function drawChat(width, height) {
            const lines = ['<Steve> gg', '<Alex> 晚上一起挖矿吗'];
            let y = height - 54;
            for (let index = lines.length - 1; index >= 0; index--) {
                ctx.font = '9px ' + FONT_STACK;
                ctx.textBaseline = 'top';
                const measured = ctx.measureText(lines[index]).width;
                fill(7, y - 1, 9 + measured + 2, y + TEXT_LINE_HEIGHT + 1, 0x66000000);
                drawText(lines[index], 8, y, 0xFFD8D8D8);
                y -= TEXT_LINE_HEIGHT + 3;
            }
        }

        function paintText(context, text, x, y, color, align) {
            if (!text) {
                return;
            }
            const colorParts = argb(color);
            context.font = '9px ' + FONT_STACK;
            context.textBaseline = 'top';
            context.textAlign = align || 'left';
            const shadow = shadowOf(color);
            context.globalAlpha = colorParts.a / 255;
            context.fillStyle = 'rgb(' + shadow.r + ', ' + shadow.g + ', ' + shadow.b + ')';
            context.fillText(text, x + 1, y + 1);
            context.fillStyle = rgb(color);
            context.fillText(text, x, y);
            context.globalAlpha = 1;
            context.textAlign = 'left';
        }

        function drawText(text, x, y, color, align) {
            paintText(ctx, text, x, y, color, align);
        }

        function measureText(text) {
            ctx.font = '9px ' + FONT_STACK;
            ctx.textBaseline = 'top';
            return ctx.measureText(text).width;
        }

        function drawPartner(context, frame) {
            const partner = frame.partner;
            const layout = frame.layout;
            if (!partner || !partner.present || partner.alpha <= 0.01) {
                return;
            }
            if (partner.ringCoverage > 0.001) {
                ringOn(
                    context,
                    layout.centerX,
                    layout.centerY,
                    layout.sizePixels * RING_RADIUS_SCALE * partner.scale,
                    Math.max(1, layout.sizePixels * RING_THICKNESS_SCALE),
                    RING_COLOR,
                    RING_OPACITY * partner.alpha,
                    START_ANGLE,
                    partner.ringCoverage
                );
            }
            const size = Math.max(4, Math.round(layout.sizePixels * ICON_SCALE));
            const wobble = WOBBLE_AMPLITUDE_RADIANS
                * Math.sin((frame.wallMillis % WOBBLE_PERIOD_MILLIS) / WOBBLE_PERIOD_MILLIS * Math.PI * 2);
            context.save();
            context.translate(layout.centerX, layout.centerY);
            context.rotate(wobble + partner.spin);
            context.scale(partner.scale, partner.scale);
            context.globalAlpha = clamp01(partner.alpha);
            drawPartnerShape(context, partner.iconId, size);
            context.restore();
            context.globalAlpha = 1;
        }

        function drawPartnerShape(context, iconId, size) {
            const radius = size / 2;
            context.fillStyle = 'rgb(234, 247, 255)';
            switch (iconId) {
                case 'moon': {
                    context.beginPath();
                    context.arc(0, 0, radius, 0, Math.PI * 2);
                    context.arc(radius * 0.42, 0, radius * 0.92, 0, Math.PI * 2, true);
                    context.fill('evenodd');
                    break;
                }
                case 'note': {
                    context.fillRect(-radius * 0.15, -radius, radius * 0.3, radius * 1.25);
                    context.beginPath();
                    context.ellipse(-radius * 0.25, radius * 0.55, radius * 0.45, radius * 0.32, -0.4, 0, Math.PI * 2);
                    context.fill();
                    context.fillRect(radius * 0.1, -radius, radius * 0.75, radius * 0.22);
                    break;
                }
                case 'bubble': {
                    const width = size * 0.95;
                    const height = size * 0.72;
                    context.beginPath();
                    context.roundRect(-width / 2, -height / 2, width, height, radius * 0.25);
                    context.fill();
                    context.beginPath();
                    context.moveTo(-width * 0.18, height / 2);
                    context.lineTo(-width * 0.30, height / 2 + height * 0.34);
                    context.lineTo(width * 0.02, height / 2);
                    context.closePath();
                    context.fill();
                    break;
                }
                default: {
                    context.beginPath();
                    for (let point = 0; point < 10; point++) {
                        const outer = point % 2 === 0;
                        const pointRadius = outer ? radius : radius * 0.46;
                        const angle = -Math.PI / 2 + (point * Math.PI) / 5;
                        const x = Math.cos(angle) * pointRadius;
                        const y = Math.sin(angle) * pointRadius;
                        if (point === 0) {
                            context.moveTo(x, y);
                        } else {
                            context.lineTo(x, y);
                        }
                    }
                    context.closePath();
                    context.fill();
                    break;
                }
            }
        }

        function drawStatusText(context, frame) {
            const text = frame.display.text;
            if (!text || text.trim().length === 0) {
                return;
            }
            const textWidth = measureText(text);
            const panelWidth = Math.max(MIN_PANEL_WIDTH, Math.ceil(textWidth) + PANEL_HORIZONTAL_PADDING * 2);
            const panelHeight = TEXT_LINE_HEIGHT + 10;
            const left = PANEL_LEFT;
            const top = frame.logicalH - panelHeight - PANEL_BOTTOM_MARGIN;
            paint(context, left, top, left + panelWidth, top + panelHeight, PANEL_BACKGROUND);
            paint(context, left, top + panelHeight - 1, left + panelWidth, top + panelHeight, ACTIVE_ACCENT);
            paintText(context, text, left + PANEL_HORIZONTAL_PADDING, top + 5, TEXT_COLOR);
        }

        function draw(frame) {
            ctx.setTransform(screen.scale, 0, 0, screen.scale, 0, 0);
            ctx.clearRect(0, 0, screen.logicalW, screen.logicalH);
            overlay.setTransform(screen.scale, 0, 0, screen.scale, 0, 0);
            overlay.clearRect(0, 0, screen.logicalW, screen.logicalH);
            drawBackground(screen.logicalW, screen.logicalH);
            drawHotbar(screen.logicalW, screen.logicalH);
            drawExperience(screen.logicalW, screen.logicalH);
            drawHearts(screen.logicalW, screen.logicalH);
            drawCrosshair(screen.logicalW, screen.logicalH);
            drawChat(screen.logicalW, screen.logicalH);

            shader.clear();
            if (!frame.settings.hudEnabled) {
                return;
            }
            if (frame.settings.iconEnabled) {
                shader.draw(frame);
                drawPartner(overlay, frame);
            }
            if (frame.settings.textEnabled) {
                drawStatusText(overlay, frame);
            }
        }

        return {
            resize,
            draw,
            shaderStatus: shader.status
        };
    }

    window.PresenceHudRenderer = { create: createRenderer };
})();
