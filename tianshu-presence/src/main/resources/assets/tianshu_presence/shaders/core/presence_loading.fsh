#version 150

// Loading: the reference liquid-dot cluster, reshaped around the handoff dot. Speed drives the pair-exchange; Morph
// collapses the six dots into one solid centre dot (1) or releases them into the six-dot cloud (0). The loading
// state enters at Morph 1, settles at 0, and collapses back to 1 when the icon hands off to a preset. The spectral
// edge comes from sampling the SDF slightly off-centre per colour channel.
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
const int N = 6;

const float SMOOTH_K = 0.08;
const float INTENSITY = 0.0025;
const float FALLOFF_P = 1.35;
const float FADE_START = 0.02;
const float FADE_END = 0.56;

const float ABERR = 0.005;
const vec3 SPECTRAL = vec3(0.0, 0.5, 1.0) * ABERR;

const float HUE_SPEED = 0.06;
const float COLOR_K = 0.5;
const float SAT = 0.01;
const float HUE_SPAN = 0.667;

const float MERGE_PERIOD = 6.0;
const float STAGGER = 0.33;
const float HOLD = 0.0;

const float W = 4.6;
const float L = 3.2;

const float PIERCE = 0.12;
const float RECOIL = 0.035;
const float REC_LAG = 0.11;

const float DOT_RADIUS = 0.035;

float hash11(float n) {
    return fract(sin(n * 127.1 + 311.7) * 43758.5453);
}

float settleWL(float tau, float w, float l) {
    if (tau <= 0.0) {
        return 0.0;
    }
    return 1.0 - exp(-l * tau) * cos(w * tau);
}

float settle(float tau) {
    return settleWL(tau, W, L);
}

float smin(float a, float b, float k) {
    float h = max(k - abs(a - b), 0.0) / k;
    return min(a, b) - h * h * k * 0.25;
}

vec3 hue2rgb(float h) {
    h = fract(h);
    float r = clamp(abs(h * 6.0 - 3.0) - 1.0, 0.0, 1.0);
    float g = clamp(2.0 - abs(h * 6.0 - 2.0), 0.0, 1.0);
    float b = clamp(2.0 - abs(h * 6.0 - 4.0), 0.0, 1.0);
    return vec3(r, g, b);
}

float dotR(float fi, float seed, float t) {
    return 0.036
            + 0.010 * sin(t * 1.3 + seed * TAU)
            + 0.005 * sin(t * 2.4 + fi * 1.3);
}

float dotSD(vec2 p, vec2 pos, float r, float t, float fi, float shapeDamp) {
    vec2 d = p - pos;
    float sq = 0.075 * (0.5 + 0.5 * sin(t * 0.9 + fi * 2.0)) * shapeDamp;
    float ca = cos(t * 0.35 + fi);
    float sa = sin(t * 0.35 + fi);
    d = mat2(ca, -sa, sa, ca) * d;
    d *= vec2(1.0 + sq, 1.0 - sq);
    return length(d) - r;
}

vec3 scene(vec2 p, float t) {
    float k = floor(t / MERGE_PERIOD);
    float u = fract(t / MERGE_PERIOD);
    float te = u * MERGE_PERIOD;
    float spread = 1.0 - clamp(Morph, 0.0, 1.0);
    float merge = max(0.001, SMOOTH_K * spread);

    vec3 total3 = vec3(1e5);
    vec3 cAcc = vec3(0.0);
    float wAcc = 1e-6;

    for (int i = 0; i < N; i++) {
        float fi = float(i);
        float seed = hash11(fi);

        float ang = fi / float(N) * TAU + t * 0.35;
        vec2 dir = vec2(cos(ang), sin(ang));

        float R = 0.17
                + 0.010 * sin(t * 1.0)
                + 0.007 * sin(t * 1.3 + seed * TAU);

        float pairId = mod(fi, 3.0);
        float moverLow = mod(k + pairId, 2.0);
        float isMover = (fi < 2.5) ? step(moverLow, 0.5) : step(0.5, moverLow);

        float goStart = pairId * STAGGER;
        float retStart = 3.0 * STAGGER + HOLD + pairId * STAGGER;

        float m = (settle(te - goStart) - settle(te - retStart)) * isMover;
        float rec = (settle(te - goStart - REC_LAG) - settle(te - retStart - REC_LAG)) * (1.0 - isMover);

        float rSelf = dotR(fi, seed, t);
        float fj = mod(fi + 3.0, 6.0);
        float rPart = dotR(fj, hash11(fj), t);

        float deep = -(R + RECOIL) - PIERCE * rPart;
        float radial = mix(R, deep, m) + RECOIL * rec;
        vec2 pos = radial * dir * spread;
        rSelf = mix(DOT_RADIUS, rSelf, spread);

        float sdR = dotSD(p - SPECTRAL.r * dir, pos, rSelf, t, fi, spread);
        float sdG = dotSD(p - SPECTRAL.g * dir, pos, rSelf, t, fi, spread);
        float sdB = dotSD(p - SPECTRAL.b * dir, pos, rSelf, t, fi, spread);
        total3 = vec3(
                smin(total3.r, sdR, merge),
                smin(total3.g, sdG, merge),
                smin(total3.b, sdB, merge));

        float hue = fract(fi / float(N) + t * HUE_SPEED) * HUE_SPAN;
        vec3 dotColor = mix(vec3(1.0), hue2rgb(hue), SAT);
        float w = exp(-sdG * COLOR_K);
        cAcc += w * dotColor;
        wAcc += w;
    }

    vec3 sd3 = max(total3, vec3(0.0)) + 1e-4;
    vec3 core3 = clamp(INTENSITY / pow(sd3, vec3(FALLOFF_P)), 0.0, 1.0);
    float fadeStart = mix(FADE_START, 0.0, clamp(Morph, 0.0, 1.0));
    float fadeEnd = mix(FADE_END, 0.015, clamp(Morph, 0.0, 1.0));
    vec3 edge3 = 1.0 - smoothstep(vec3(fadeStart), vec3(fadeEnd), sd3);
    vec3 bright = core3 * edge3;

    return bright * (cAcc / wAcc);
}

vec4 over(vec4 top, vec4 bottom) {
    float alpha = top.a + bottom.a * (1.0 - top.a);
    if (alpha <= 0.0001) {
        return vec4(0.0);
    }
    vec3 rgb = (top.rgb * top.a + bottom.rgb * bottom.a * (1.0 - top.a)) / alpha;
    return vec4(rgb, alpha);
}

void main() {
    vec2 q = texCoord0 * 2.0 - 1.0;
    float t = Time * (0.75 + Speed * 0.4);
    vec2 p = q * (0.50 + Convergence * 0.2);
    p /= 1.0 + 0.03 * sin(t);

    vec3 color = scene(p, t);
    color *= 1.0 + 0.05 * sin(t + 1.0);
    color = pow(color, vec3(1.0 / 1.2));
    color = min(color, vec3(1.0));
    color *= 0.9 + 0.35 * Intensity;

    float alpha = clamp(dot(color, vec3(0.299, 0.587, 0.114)) * 1.8, 0.0, 1.0) * Opacity;
    vec4 result = vec4(color, alpha);

    if (Task > 0.5) {
        float blink = 0.55 + 0.45 * sin(t * 6.0);
        float aa = fwidth(length(q)) + 0.002;
        vec2 offset = vec2(0.66, -0.66);
        float halo = 1.0 - smoothstep(0.115, 0.115 + aa, length(q - offset));
        float dot = 1.0 - smoothstep(0.085, 0.085 + aa, length(q - offset));
        result = over(vec4(vec3(0.02, 0.04, 0.06), 0.35 * halo * Opacity), result);
        result = over(vec4(vec3(1.0), (0.85 + 0.15 * blink) * dot * Opacity), result);
    }
    fragColor = result;
}
