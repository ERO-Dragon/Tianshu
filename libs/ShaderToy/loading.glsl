const float TAU = 6.28318530718;
const int   N   = 6;

// ---- smin 融合 ----
const float SMOOTH_K = 0.08;  // 融合柔度：越大液颈越粗、越「黏」；
                              // 参考量级 ≈ 2~3 倍球半径（球半径 ~0.036）

// ---- falloff / glow（在合并后的距离场上做，每通道一次）----
const float INTENSITY  = 0.0025; // 越大光晕越亮越外扩
const float FALLOFF_P  = 1.35;  // 反比幂次：对应 v29 的 GLOW_TIGHT 角色
const float FADE_START = 0.02;  // 距表面多远开始渐隐（往小=光晕更早收，球更小）
const float FADE_END   = 0.56;  // 光晕彻底归零的距离（大软晕；想收紧改 0.16~0.28）

// ---- 光谱彩边（苹果 aberration 的 3 样本版）----
const float ABERR = 0.005;    // 径向色散距离 ≈ 球半径的 1/3；
                              // 翻号可交换红/蓝内外；0 = 关闭彩边
const vec3  SPECTRAL = vec3(0.0, 0.5, 1.0) * ABERR;   // R/G/B 各自的偏移

// ---- 颜色（hue 沿排列分布 + 随时间流动）----
const float HUE_SPEED = 0.06;  // 彩虹整体流速（圈/秒），负值反向
const float COLOR_K   = 0.5;  // 颜色权重锐度：越大色区边界越分明，
                               // 越小相邻球颜色越互相渗透
const float SAT       = 0.01;  // 饱和度：1=纯彩虹，往下掺白更接近 Siri 的粉彩感
const float HUE_SPAN  = 0.667; // 苹果光谱只走 0..240°（红→绿→蓝），不绕品红

// ---- 节奏（与 v29 完全一致）----
const float MERGE_PERIOD = 6.0;
const float T_MOVE   = 1.25;
const float STAGGER  = 0.33;
const float HOLD     = 0.0;

const float W = 4.6;
const float L = 3.2;

const float PIERCE  = 0.12;
const float RECOIL  = 0.035;
const float REC_LAG = 0.11;

// ---- 全员向心聚合（与 v29 一致，仅 GATHER_DIM 放松）----
const float GATHER_PERIOD = 12.0;
const float GATHER_START  = 9.2;
const float GATHER_HOLD   = 0.8;
const float GATHER_R      = 0.008;
const float GATHER_DIM    = 0.85;  // smin 不叠加，只需轻压一点防 flash 过曝
const float GATHER_IN     = 1.8;
const float GATHER_IN_L   = 7.5;
const float BURST_W = 6.5;
const float BURST_L = 4.0;

const float CHARGE_T     = 0.30;
const float CHARGE_SHRK  = 0.18;
const float CHARGE_GLOW  = 0.35;
const float FLASH_GAIN   = 1.2;
const float FLASH_DECAY  = 7.0;

float hash11(float n){ return fract(sin(n*127.1 + 311.7)*43758.5453); }

// 欠阻尼台阶（带过冲回弹）——成对融合 & 爆开用
float settleWL(float tau, float w, float l){
    if(tau <= 0.0) return 0.0;
    return 1.0 - exp(-l*tau)*cos(w*tau);
}
float settle(float tau){ return settleWL(tau, W, L); }

// 临界阻尼台阶（无过冲，单调逼近）——收拢用
float settleCrit(float tau, float l){
    if(tau <= 0.0) return 0.0;
    return 1.0 - exp(-l*tau)*(1.0 + l*tau);
}

// 经典 polynomial smooth-min（即逆向出的那个 ×0.25 版本）
float smin(float a, float b, float k){
    float h = max(k - abs(a - b), 0.0) / k;
    return min(a, b) - h*h*k*0.25;
}

// hue → RGB：三条错相三角波（与苹果 %262/%265/%268 同构）
vec3 hue2rgb(float h){
    h = fract(h);
    float r = clamp(abs(h*6.0 - 3.0) - 1.0, 0.0, 1.0);
    float g = clamp(2.0 - abs(h*6.0 - 2.0), 0.0, 1.0);
    float b = clamp(2.0 - abs(h*6.0 - 4.0), 0.0, 1.0);
    return vec3(r, g, b);
}

float dotR(float fi, float seed, float t){
    return 0.036
         + 0.010*sin(t*1.3 + seed*TAU)
         + 0.005*sin(t*2.4 + fi*1.3);
}

// 单球带符号距离（含温和椭圆挤压；shapeDamp=0 时退化为正圆）
// 注意：各向异性缩放后的 length 不是严格度量距离，但形变很小（≤7.5%），
// 喂给 smin 视觉上无碍
float dotSD(vec2 p, vec2 pos, float r, float t, float fi, float shapeDamp){
    vec2 d = p - pos;
    float sq = 0.075 * (0.5 + 0.5*sin(t*0.9 + fi*2.0)) * shapeDamp;
    float ca = cos(t*0.35 + fi), sa = sin(t*0.35 + fi);
    d = mat2(ca,-sa,sa,ca) * d;
    d *= vec2(1.0+sq, 1.0-sq);
    return length(d) - r;
}

vec3 scene(vec2 p, float t){
    float k  = floor(t/MERGE_PERIOD);
    float u  = fract(t/MERGE_PERIOD);
    float te = u * MERGE_PERIOD;

    // 全员聚散包络：临界阻尼吸入 + 高频欠阻尼爆开（与 v29 一致）
    float tg = mod(t, GATHER_PERIOD);
    float g  = settleCrit((tg - GATHER_START) * GATHER_IN, GATHER_IN_L)
             - settleWL(tg - GATHER_START - GATHER_HOLD, BURST_W, BURST_L);
    float gC = clamp(g, 0.0, 1.0);

    float tb     = tg - (GATHER_START + GATHER_HOLD);
    float charge = smoothstep(-CHARGE_T, 0.0, min(tb, 0.0)) * gC;
    float flash  = tb > 0.0 ? exp(-tb * FLASH_DECAY) : 0.0;

    // 亮度修饰：蓄力增亮 + 爆闪 + 合体轻压
    float gBright = mix(1.0, GATHER_DIM, gC)
                  * (1.0 + CHARGE_GLOW*charge + FLASH_GAIN*flash);

    // —— 距离场融合（R/G/B 三通道光谱）+ 颜色加权 ——
    vec3  total3 = vec3(1e5);
    vec3  cAcc   = vec3(0.0);
    float wAcc   = 1e-6;

    for(int i=0; i<N; i++){
        float fi   = float(i);
        float seed = hash11(fi);

        float ang = fi/float(N)*TAU + t*0.35;
        vec2 dir  = vec2(cos(ang), sin(ang));

        float R = 0.17
                + 0.010*sin(t*1.0)
                + 0.007*sin(t*1.3 + seed*TAU);

        float pairId   = mod(fi, 3.0);
        float moverLow = mod(k + pairId, 2.0);
        float isMover  = (fi < 2.5) ? step(moverLow, 0.5)
                                    : step(0.5, moverLow);

        float goStart  = pairId * STAGGER;
        float retStart = 3.0*STAGGER + HOLD + pairId * STAGGER;

        float m   = (settle(te - goStart)           - settle(te - retStart))           * isMover;
        float rec = (settle(te - goStart - REC_LAG) - settle(te - retStart - REC_LAG)) * (1.0 - isMover);

        float rSelf = dotR(fi, seed, t);
        rSelf = mix(rSelf, 0.036, gC);
        rSelf *= 1.0 - CHARGE_SHRK * charge;
        float fj    = mod(fi + 3.0, 6.0);
        float rPart = dotR(fj, hash11(fj), t);

        float deep   = -(R + RECOIL) - PIERCE * rPart;
        float radial = mix(R, deep, m) + RECOIL * rec;
        radial = mix(radial, GATHER_R, g);
        vec2  pos    = radial * dir;

        // ① 距离场融合：R/G/B 各用一个沿本球径向偏移过的采样点。
        //    内部三通道都饱和 → 白；边缘/液颈处先后越界 → 彩边贴液面
        float sdR = dotSD(p - SPECTRAL.r*dir, pos, rSelf, t, fi, 1.0 - gC);
        float sdG = dotSD(p - SPECTRAL.g*dir, pos, rSelf, t, fi, 1.0 - gC);
        float sdB = dotSD(p - SPECTRAL.b*dir, pos, rSelf, t, fi, 1.0 - gC);
        total3 = vec3( smin(total3.r, sdR, SMOOTH_K),
                       smin(total3.g, sdG, SMOOTH_K),
                       smin(total3.b, sdB, SMOOTH_K) );

        // ② 颜色：hue 沿排列均布（只走红→绿→蓝 240°）+ 随时间流动；
        //    权重 exp(-sd·K)，用中间通道 G 的距离
        float hue = fract(fi/float(N) + t*HUE_SPEED) * HUE_SPAN;
        vec3 dotCol = mix(vec3(1.0), hue2rgb(hue), SAT);
        float w = exp(-sdG * COLOR_K);
        cAcc += w * dotCol;
        wAcc += w;
    }

    // —— falloff + glow：每通道在各自合并场上算一次（结构同苹果）——
    vec3 sd3    = max(total3, vec3(0.0)) + 1e-4;   // 球内 clamp 到 0 → 实心平台
    vec3 core3  = clamp(INTENSITY / pow(sd3, vec3(FALLOFF_P)), 0.0, 1.0);
    vec3 edge3  = 1.0 - smoothstep(vec3(FADE_START), vec3(FADE_END), sd3);
    vec3 bright = core3 * edge3 * gBright;

    return bright * (cAcc / wAcc);
}

void mainImage(out vec4 fragColor, in vec2 fragCoord){
    vec2 res = iResolution.xy;
    vec2 p = (2.0*fragCoord - res) / min(res.x, res.y);
    float t = iTime;
    p /= 1.0 + 0.03*sin(t*1.0);            // 整团呼吸

    vec3 col = scene(p, t);

    col *= 1.0 + 0.05*sin(t*1.0 + 1.0);   // 峰值会被后面 min 钳掉，谷值就是纯白   // 亮度呼吸

    col = pow(col, vec3(1.0/1.2));
    col = min(col, 1.0);                   // 球内已是平台，钳位只作保险
    // （dFdy 屏幕空间色差已删：彩边现在由光谱 SDF 偏移产生，全向贴液面）

    float n = fract(sin(dot(fragCoord, vec2(12.9898,78.233)))*43758.5453);
    col += (n - 0.5)/255.0;

    fragColor = vec4(col, 1.0);
}
