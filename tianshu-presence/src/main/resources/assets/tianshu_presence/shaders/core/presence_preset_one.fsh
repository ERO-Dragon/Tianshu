#version 150

uniform float Time;
uniform float Intensity;
uniform float Speed;
uniform float Convergence;
uniform float Pulse;
uniform float Opacity;
uniform float State;
uniform float Task;

in vec2 texCoord0;
out vec4 fragColor;

const float PRESET = 0.0;
const float TWO_PI = 6.28318530718;

float stateBias() {
    if (State > 3.5) {
        return 0.22;
    }
    if (State > 2.5) {
        return 0.72;
    }
    if (State > 1.5) {
        return 0.48;
    }
    return 0.34;
}

vec3 stateColor(float t) {
    float phase = fract(t * (PRESET > 0.5 ? 0.06 : 0.10));
    vec3 first = PRESET > 0.5 ? vec3(0.698, 0.549, 1.0) : vec3(0.471, 0.910, 1.0);
    vec3 second = PRESET > 0.5 ? vec3(0.416, 0.549, 1.0) : vec3(1.0, 0.553, 0.847);
    return mix(first, second, fract(phase + stateBias()));
}

vec4 over(vec4 top, vec4 bottom) {
    float alpha = top.a + bottom.a * (1.0 - top.a);
    if (alpha <= 0.0001) {
        return vec4(0.0);
    }
    vec3 rgb = (top.rgb * top.a + bottom.rgb * bottom.a * (1.0 - top.a)) / alpha;
    return vec4(rgb, alpha);
}

float discMask(float r, float radius, float aa) {
    return 1.0 - smoothstep(radius - aa, radius + aa, r);
}

float bandMask(float r, float radius, float halfWidth, float aa) {
    return 1.0 - smoothstep(halfWidth, halfWidth + aa, abs(r - radius));
}

float arcMask(float angle, float rotation, float coverage, float aa) {
    float relative = mod(angle - rotation + TWO_PI, TWO_PI);
    float end = coverage * TWO_PI;
    return 1.0 - smoothstep(end - aa, end + aa, relative);
}

vec4 baseIcon(vec2 p, float t) {
    float r = length(p);
    float aa = fwidth(r) * 1.2 + 0.0005;
    vec3 tint = stateColor(t);
    vec4 acc = vec4(0.0);

    float halo = 1.0 - smoothstep(0.34, 1.0, r);
    acc = over(vec4(tint, 0.16 * Intensity * Opacity * halo), acc);

    float lobes = PRESET > 0.5 ? 8.0 : 6.0;
    float orbit = 2.0 * (0.10 + (1.0 - Convergence) * 0.12);
    for (int i = 0; i < 8; i++) {
        if (float(i) >= lobes) {
            break;
        }
        float fi = float(i);
        float angle = fi / lobes * TWO_PI + t * (0.18 + Speed * 0.22);
        float wobble = 1.0 + 0.20 * sin(t * 1.7 + fi * 1.31);
        float lobeRadius = 2.0 * (PRESET > 0.5 ? 0.18 : 0.16) * wobble;
        vec2 centre = vec2(cos(angle), sin(angle)) * orbit;
        acc = over(vec4(tint, 0.26 * Intensity * Opacity
                * discMask(length(p - centre), lobeRadius, aa)), acc);
    }

    float pulse = 1.0 + 0.06 * sin(t * (1.2 + Speed));
    float outerRadius = 2.0 * (0.42 + Intensity * 0.09) * pulse;
    acc = over(vec4(tint, 0.66 * Intensity * Opacity * discMask(r, outerRadius, aa)), acc);

    float innerRadius = outerRadius * (0.52 + Convergence * 0.18);
    acc = over(vec4(vec3(1.0), 0.40 * Intensity * Opacity * discMask(r, innerRadius, aa)), acc);

    float angle = atan(p.y, p.x);
    float angleAA = fwidth(angle) * 1.5 + 0.002;
    float rotation = t * (0.16 + Speed * 0.16);
    float ring = bandMask(r, 2.0 * 0.46, 0.055, aa) * arcMask(angle, rotation, 0.60, angleAA);
    acc = over(vec4(tint, (0.34 + 0.34 * Intensity) * Opacity * ring), acc);

    if (Pulse > 0.01) {
        float wavePhase = fract(t * (0.34 + Speed * 0.18));
        float wave = bandMask(r, 2.0 * (0.48 + wavePhase * 0.46), 0.045, aa);
        float waveAlpha = (1.0 - wavePhase) * (0.20 + Pulse * 0.45) * Opacity;
        acc = over(vec4(tint, waveAlpha * wave), acc);
    }

    if (Task > 0.5) {
        float taskPulse = 0.55 + 0.45 * sin(t * 4.0);
        float offset = 2.0 * 0.34;
        float dot = discMask(length(p - vec2(offset, offset)), 2.0 * 0.09, aa);
        acc = over(vec4(vec3(0.92, 0.97, 1.0), (0.45 + 0.45 * taskPulse) * dot), acc);
    }
    return acc;
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}

void main() {
    vec2 p = texCoord0 * 2.0 - 1.0;
    float t = Time * (0.7 + Speed * 0.3);
    float r = length(p);
    float a = atan(p.y, p.x);
    float n = noise(p * 4.0 + vec2(t * 0.6, -t * 0.35));
    float waves = 0.5 + 0.5 * sin(a * 7.0 - t * 2.0 + n * 3.0);
    float surface = 1.0 - smoothstep(0.36 + 0.04 * waves, 0.60 + 0.05 * waves, r);
    float edge = 1.0 - smoothstep(0.42, 0.84, r);
    float core = smoothstep(0.56, 0.10 + Convergence * 0.12, r);
    float pulse = 1.0 + 0.10 * sin(t * 1.4) + Pulse * 0.16;
    vec3 warm = vec3(1.0, 0.42, 0.80);
    vec3 cool = vec3(0.28, 0.88, 1.0);
    vec3 color = mix(cool, warm, 0.5 + 0.5 * sin(a * 0.8 + t * 0.4 + State));
    float alpha = max(surface * 0.88, edge * 0.24) * Intensity;
    vec4 detail = vec4(color * (0.56 + core * 0.68) * pulse, alpha * Opacity);
    fragColor = over(detail, baseIcon(p, Time));
}
