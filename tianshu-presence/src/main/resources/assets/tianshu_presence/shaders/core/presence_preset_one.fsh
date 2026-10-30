#version 150

// Preset one: the fluid bubble from the reference shader. The whole icon - the iridescent shell, the turbulent
// interior and the bright core - is evaluated per fragment. The HUD uniforms only bias the reference: Speed scales
// time, Convergence shrinks the bubble, Intensity brightens it, State rotates the palette a little, Pulse adds the
// listening ripple and Task adds the background-work dot.
uniform float Time;
uniform float Intensity;
uniform float Speed;
uniform float Convergence;
uniform float Pulse;
uniform float Opacity;
uniform float Morph;
uniform float State;
uniform float Task;

in vec2 texCoord0;
out vec4 fragColor;

const float TAU = 6.28318530718;
const float HANDOFF_DOT = 0.085;

float rand(vec2 n) {
    return fract(sin(dot(n, vec2(12.9898, 4.1414))) * 43758.5453);
}

float noise(vec2 p) {
    vec2 ip = floor(p);
    vec2 u = fract(p);
    u = u * u * (3.0 - 2.0 * u);
    float res = mix(
            mix(rand(ip), rand(ip + vec2(1.0, 0.0)), u.x),
            mix(rand(ip + vec2(0.0, 1.0)), rand(ip + vec2(1.0, 1.0)), u.x),
            u.y);
    return res * res;
}

float fbm(vec2 p, int octaves) {
    float sum = 0.0;
    float weight = 0.0;
    float amplitude = 0.5;
    for (int index = 0; index < 4; index++) {
        if (index >= octaves) {
            break;
        }
        sum += amplitude * noise(p);
        weight += amplitude;
        amplitude *= 0.5;
        p *= 2.0;
    }
    return sum / weight;
}

vec3 palette(float t) {
    return 0.3 + 0.5 * cos(TAU * (vec3(1.0) * t + vec3(0.0, 0.8, 0.8)));
}

float luma(vec3 color) {
    return dot(color, vec3(0.299, 0.587, 0.114));
}

vec4 over(vec4 top, vec4 bottom) {
    float alpha = top.a + bottom.a * (1.0 - top.a);
    if (alpha <= 0.0001) {
        return vec4(0.0);
    }
    vec3 rgb = (top.rgb * top.a + bottom.rgb * bottom.a * (1.0 - top.a)) / alpha;
    return vec4(rgb, alpha);
}

vec4 overlay(vec4 base, vec2 q, float t, float morph) {
    if (Pulse > 0.01) {
        for (int ringIndex = 0; ringIndex < 2; ringIndex++) {
            float phase = fract(t * 0.30 + float(ringIndex) * 0.5);
            float radius = mix(0.86, 0.995, phase);
            float aa = fwidth(length(q)) + 0.003;
            float ring = 1.0 - smoothstep(0.022, 0.022 + aa, abs(length(q) - radius));
            float strength = sqrt(1.0 - phase) * Pulse * 0.9 * (1.0 - morph);
            base = over(vec4(vec3(0.45, 0.92, 1.0), ring * strength * Opacity), base);
        }
    }
    if (Task > 0.5) {
        float blink = 0.55 + 0.45 * sin(t * 6.0);
        float aa = fwidth(length(q)) + 0.002;
        vec2 offset = vec2(0.66, -0.66);
        float halo = 1.0 - smoothstep(0.115, 0.115 + aa, length(q - offset));
        float dot = 1.0 - smoothstep(0.085, 0.085 + aa, length(q - offset));
        base = over(vec4(vec3(0.02, 0.04, 0.06), 0.35 * halo * Opacity), base);
        base = over(vec4(vec3(1.0), (0.85 + 0.15 * blink) * dot * Opacity), base);
    }
    return base;
}

// ---- loading entrance (owned by the preset, not a second program) ----
const float LOAD_TAU = 6.28318530718;
const int LOAD_N = 6;
const float LOAD_SMOOTH_K = 0.08;
const float LOAD_INTENSITY = 0.0025;
const float LOAD_FALLOFF_P = 1.35;
const float LOAD_FADE_START = 0.02;
const float LOAD_FADE_END = 0.56;
const float LOAD_ABERR = 0.005;
const vec3 LOAD_SPECTRAL = vec3(0.0, 0.5, 1.0) * LOAD_ABERR;
const float LOAD_HUE_SPEED = 0.06;
const float LOAD_COLOR_K = 0.5;
const float LOAD_SAT = 0.01;
const float LOAD_HUE_SPAN = 0.667;
const float LOAD_MERGE_PERIOD = 6.0;
const float LOAD_STAGGER = 0.33;
const float LOAD_HOLD = 0.0;
const float LOAD_W = 4.6;
const float LOAD_L = 3.2;
const float LOAD_PIERCE = 0.12;
const float LOAD_RECOIL = 0.035;
const float LOAD_REC_LAG = 0.11;
const float LOAD_DOT_RADIUS = 0.035;

float loadHash11(float n) {
    return fract(sin(n * 127.1 + 311.7) * 43758.5453);
}

float loadSettleWL(float tau, float w, float l) {
    if (tau <= 0.0) {
        return 0.0;
    }
    return 1.0 - exp(-l * tau) * cos(w * tau);
}

float loadSettle(float tau) {
    return loadSettleWL(tau, LOAD_W, LOAD_L);
}

float loadSmin(float a, float b, float k) {
    float h = max(k - abs(a - b), 0.0) / k;
    return min(a, b) - h * h * k * 0.25;
}

vec3 loadHue2rgb(float h) {
    h = fract(h);
    float r = clamp(abs(h * 6.0 - 3.0) - 1.0, 0.0, 1.0);
    float g = clamp(2.0 - abs(h * 6.0 - 2.0), 0.0, 1.0);
    float b = clamp(2.0 - abs(h * 6.0 - 4.0), 0.0, 1.0);
    return vec3(r, g, b);
}

float loadDotR(float fi, float seed, float t) {
    return 0.036
            + 0.010 * sin(t * 1.3 + seed * LOAD_TAU)
            + 0.005 * sin(t * 2.4 + fi * 1.3);
}

float loadDotSD(vec2 p, vec2 pos, float r, float t, float fi, float shapeDamp) {
    vec2 d = p - pos;
    float sq = 0.075 * (0.5 + 0.5 * sin(t * 0.9 + fi * 2.0)) * shapeDamp;
    float ca = cos(t * 0.35 + fi);
    float sa = sin(t * 0.35 + fi);
    d = mat2(ca, -sa, sa, ca) * d;
    d *= vec2(1.0 + sq, 1.0 - sq);
    return length(d) - r;
}

vec3 loadScene(vec2 p, float t, float spread) {
    float spreadAmount = clamp(spread, 0.0, 1.0);
    float dotness = 1.0 - spreadAmount;
    float merge = max(0.001, LOAD_SMOOTH_K * spreadAmount);
    float k = floor(t / LOAD_MERGE_PERIOD);
    float u = fract(t / LOAD_MERGE_PERIOD);
    float te = u * LOAD_MERGE_PERIOD;

    vec3 total3 = vec3(1e5);
    vec3 cAcc = vec3(0.0);
    float wAcc = 1e-6;

    for (int i = 0; i < LOAD_N; i++) {
        float fi = float(i);
        float seed = loadHash11(fi);

        float ang = fi / float(LOAD_N) * LOAD_TAU + t * 0.35;
        vec2 dir = vec2(cos(ang), sin(ang));

        float R = 0.17
                + 0.010 * sin(t * 1.0)
                + 0.007 * sin(t * 1.3 + seed * LOAD_TAU);

        float pairId = mod(fi, 3.0);
        float moverLow = mod(k + pairId, 2.0);
        float isMover = (fi < 2.5) ? step(moverLow, 0.5) : step(0.5, moverLow);

        float goStart = pairId * LOAD_STAGGER;
        float retStart = 3.0 * LOAD_STAGGER + LOAD_HOLD + pairId * LOAD_STAGGER;

        float m = (loadSettle(te - goStart) - loadSettle(te - retStart)) * isMover;
        float rec = (loadSettle(te - goStart - LOAD_REC_LAG) - loadSettle(te - retStart - LOAD_REC_LAG))
                * (1.0 - isMover);

        float rSelf = loadDotR(fi, seed, t);
        float fj = mod(fi + 3.0, 6.0);
        float rPart = loadDotR(fj, loadHash11(fj), t);

        float deep = -(R + LOAD_RECOIL) - LOAD_PIERCE * rPart;
        float radial = mix(R, deep, m) + LOAD_RECOIL * rec;
        vec2 pos = radial * dir * spreadAmount;
        rSelf = mix(LOAD_DOT_RADIUS, rSelf, spreadAmount);

        float sdR = loadDotSD(p - LOAD_SPECTRAL.r * dir, pos, rSelf, t, fi, spreadAmount);
        float sdG = loadDotSD(p - LOAD_SPECTRAL.g * dir, pos, rSelf, t, fi, spreadAmount);
        float sdB = loadDotSD(p - LOAD_SPECTRAL.b * dir, pos, rSelf, t, fi, spreadAmount);
        total3 = vec3(
                loadSmin(total3.r, sdR, merge),
                loadSmin(total3.g, sdG, merge),
                loadSmin(total3.b, sdB, merge));

        float hue = fract(fi / float(LOAD_N) + t * LOAD_HUE_SPEED) * LOAD_HUE_SPAN;
        vec3 dotColor = mix(vec3(1.0), loadHue2rgb(hue), LOAD_SAT);
        float w = exp(-sdG * LOAD_COLOR_K);
        cAcc += w * dotColor;
        wAcc += w;
    }

    vec3 sd3 = max(total3, vec3(0.0)) + 1e-4;
    vec3 core3 = clamp(LOAD_INTENSITY / pow(sd3, vec3(LOAD_FALLOFF_P)), 0.0, 1.0);
    float fadeStart = mix(LOAD_FADE_START, 0.0, dotness);
    float fadeEnd = mix(LOAD_FADE_END, 0.015, dotness);
    vec3 edge3 = 1.0 - smoothstep(vec3(fadeStart), vec3(fadeEnd), sd3);

    return core3 * edge3 * (cAcc / wAcc);
}

void main() {
    vec2 q = texCoord0 * 2.0 - 1.0;
    float t = Time * (0.65 + Speed * 0.35);
    float morph = clamp(Morph, 0.0, 1.0);
    float orbMorph = clamp(morph * 2.0, 0.0, 1.0);
    float soap = step(2.5, State) * (1.0 - step(3.5, State));
    float swayAngle = atan(q.y, q.x);
    float sway = soap * (0.030 * sin(swayAngle * 4.0 + t * 1.7)
            + 0.012 * sin(swayAngle * 7.0 - t * 2.3));
    vec2 uv = q * mix(1.85, 1.05, 1.0 - Convergence) * (1.0 + sway);
    float l = dot(uv, uv);
    if (l > 2.5) {
        fragColor = vec4(0.0);
        return;
    }

    float sm = smoothstep(1.02, 0.98, l);
    float depth = sm * l * l * l * 2.0;
    vec3 normal = normalize(vec3(uv.x, uv.y, 0.7 - depth));
    float nx = fbm(uv * 2.0 + t * 0.4 + 25.69, 4);
    float ny = fbm(uv * 2.0 + t * 0.4 + 86.31, 4);
    float n = fbm(uv * 3.0 + 2.0 * vec2(nx, ny), 3);

    vec3 color = vec3(n * 0.5 + 0.25);
    float angle = atan(uv.y, uv.x) / TAU + t * 0.1 + State * 0.02;
    color *= palette(angle);
    color *= 2.0;

    vec3 tint = abs(color);
    vec3 lit = color * depth;
    lit += (lit * 0.5 + vec3(1.0) - luma(lit))
            * vec3(max(0.0, pow(dot(normal, vec3(0.0, 0.0, -1.0)), 5.0) * 3.0));
    lit += 1.5 * smoothstep(0.6, 1.0, fbm(normal.xy * 3.0 / (1.0 + normal.z), 2)) * depth;
    color = lit + color * pow(
            (1.0 - smoothstep(1.0, 0.98, l) - pow(max(0.0, length(uv) - 1.0), 0.2)) * 2.0,
            4.0);

    float shell = fbm(normalize(uv) * 2.0 + t, 2) + 0.1;
    vec2 inner = uv * (shell + 0.1) * 0.5;
    vec3 sparkle = normalize(tint + 0.0001) + 0.1;
    float sparkleDepth = 0.2 + pow(smoothstep(0.0, 1.5, sqrt(dot(inner, inner))) * 48.0, 0.25);
    sparkleDepth *= sparkleDepth * sparkleDepth * sparkleDepth;
    sparkleDepth = 1.0 / sparkleDepth;
    sparkle *= sparkleDepth;
    color += sparkle * sparkle * sm * smoothstep(0.7, 1.0, sparkleDepth);
    color += abs(normal) * (1.0 - depth) * sm * 0.25;
    color = max(color, vec3(0.0));

    float gain = 0.6 + 0.9 * Intensity;
    float revealRadius = mix(1.20, HANDOFF_DOT, orbMorph);
    float discRadius = mix(0.80, HANDOFF_DOT, orbMorph);
    float soft = 0.02 + 0.14 * (1.0 - orbMorph);
    float reveal = 1.0 - smoothstep(revealRadius - soft, revealRadius + soft, length(q));
    float alpha = clamp(luma(color) * gain * 1.35, 0.0, 1.0)
            * (1.0 - smoothstep(1.35, 1.75, length(uv))) * reveal;
    vec4 orbLayer = vec4(color * gain * (1.0 + 0.08 * Pulse), alpha * Opacity);

    float discEdge = 0.012 + 0.02 * orbMorph;
    float discMask = 1.0 - smoothstep(discRadius - discEdge, discRadius + discEdge, length(q));
    float birth = smoothstep(0.0, 0.35, orbMorph);
    vec3 birthColor = mix(vec3(0.55, 0.86, 1.0), vec3(1.0), orbMorph);
    orbLayer = over(vec4(birthColor, discMask * birth * Opacity), orbLayer);

    float loadingT = Time * (0.75 + Speed * 0.4);
    float loadingSpread = smoothstep(0.5, 1.0, morph);
    vec2 loadingPoint = q * (0.50 + Convergence * 0.2);
    loadingPoint /= 1.0 + 0.03 * sin(loadingT);
    vec3 loadingColor = loadScene(loadingPoint, loadingT, loadingSpread);
    loadingColor *= 1.0 + 0.05 * sin(loadingT + 1.0);
    loadingColor = pow(loadingColor, vec3(1.0 / 1.2));
    loadingColor = min(loadingColor, vec3(1.0));
    loadingColor *= 0.9 + 0.35 * Intensity;
    float loadingAlpha = clamp(dot(loadingColor, vec3(0.299, 0.587, 0.114)) * 1.8, 0.0, 1.0) * Opacity;
    vec4 loadingLayer = vec4(loadingColor, loadingAlpha);

    float loadingWeight = smoothstep(0.45, 0.55, morph);
    vec4 combined = mix(orbLayer, loadingLayer, loadingWeight);
    float overlayMorph = 1.0 - smoothstep(0.0, 0.5, morph);
    fragColor = overlay(combined, q, t, overlayMorph);
}
