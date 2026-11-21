#include <metal_stdlib>
using namespace metal;

// Java packs every triangle as 12 aligned float4 values (48 floats / 192 bytes).
struct GpuTriangle {
    float4 p0;
    float4 p1;
    float4 p2;
    float4 n0;
    float4 n1;
    float4 n2;
    float4 uv01;       // uv0.xy, uv1.xy
    float4 uv2meta;    // uv2.xy, texture-array layer, roughness
    float4 albedo;
    float4 specular;
    float4 emission;
    float4 extra;      // reserved, has-UV flag
};

struct SurfaceHit {
    float t;
    float3 point;
    float3 normal;
    float2 uv;
    uint triangleIndex;
    bool found;
    bool isWater;
};

inline float saturate1(float x) { return clamp(x, 0.0f, 1.0f); }
inline float3 safeNormalize(float3 v) {
    float len2 = dot(v, v);
    return len2 > 1.0e-16f ? v * rsqrt(len2) : float3(0.0f, 1.0f, 0.0f);
}
inline float random01(thread uint &state) {
    state ^= state << 13;
    state ^= state >> 17;
    state ^= state << 5;
    return float(state) * (1.0f / 4294967295.0f);
}
inline uint seedFor(uint x, uint y, uint sampleIndex) {
    uint v = x * 0x9e3779b9u ^ y * 0x85ebca6bu ^ sampleIndex * 0xc2b2ae35u ^ 0x68bc21ebu;
    v ^= v >> 16; v *= 0x7feb352du; v ^= v >> 15; v *= 0x846ca68bu; v ^= v >> 16;
    return v == 0 ? 1u : v;
}

inline bool intersectBox(float3 ro, float3 rd, float3 bmin, float3 bmax, float maxT) {
    float tNear = 1.0e-4f;
    float tFar = maxT;
    for (uint axis = 0; axis < 3; ++axis) {
        float origin = ro[axis];
        float direction = rd[axis];
        if (abs(direction) < 1.0e-8f) {
            if (origin < bmin[axis] || origin > bmax[axis]) return false;
        } else {
            float inv = 1.0f / direction;
            float a = (bmin[axis] - origin) * inv;
            float b = (bmax[axis] - origin) * inv;
            if (a > b) { float temp = a; a = b; b = temp; }
            tNear = max(tNear, a);
            tFar = min(tFar, b);
            if (tFar < tNear) return false;
        }
    }
    return true;
}

inline bool intersectTriangle(float3 ro, float3 rd, const device GpuTriangle &tri,
                              float maxT, thread SurfaceHit &hit, uint triangleIndex) {
    float3 a = tri.p0.xyz;
    float3 e1 = tri.p1.xyz - a;
    float3 e2 = tri.p2.xyz - a;
    float3 p = cross(rd, e2);
    float det = dot(e1, p);
    if (abs(det) < 1.0e-8f) return false;
    float invDet = 1.0f / det;
    float3 tvec = ro - a;
    float u = dot(tvec, p) * invDet;
    if (u < 0.0f || u > 1.0f) return false;
    float3 q = cross(tvec, e1);
    float v = dot(rd, q) * invDet;
    if (v < 0.0f || u + v > 1.0f) return false;
    float t = dot(e2, q) * invDet;
    if (t < 1.0e-4f || t >= maxT) return false;

    float w = 1.0f - u - v;
    float3 n = safeNormalize(tri.n0.xyz * w + tri.n1.xyz * u + tri.n2.xyz * v);
    if (dot(n, rd) > 0.0f) n = -n;
    hit.t = t;
    hit.point = ro + rd * t;
    hit.normal = n;
    hit.uv = tri.uv01.xy * w + tri.uv01.zw * u + tri.uv2meta.xy * v;
    hit.triangleIndex = triangleIndex;
    hit.found = true;
    hit.isWater = false;
    return true;
}

inline float3 waveContribution(float x, float z, float2 direction,
                               float baseAmplitude, float baseWavelength, float phase,
                               float amplitudeScale, float wavelengthScale) {
    float amplitude = baseAmplitude * amplitudeScale;
    float k = 6.28318530718f / max(0.001f, baseWavelength * wavelengthScale);
    float angle = k * dot(direction, float2(x, z)) + phase;
    float c = cos(angle);
    return float3(amplitude * sin(angle), amplitude * k * c * direction.x,
                  amplitude * k * c * direction.y);
}

// x = surface height offset; y/z = height derivatives with respect to world x/z.
inline float3 oceanField(float x, float z, float amplitudeScale, float wavelengthScale) {
    float3 field = float3(0.0f);
    field += waveContribution(x, z, normalize(float2(1.00f, 0.40f)), 0.12f, 3.8f, 0.40f, amplitudeScale, wavelengthScale);
    field += waveContribution(x, z, normalize(float2(0.62f, -0.78f)), 0.085f, 2.1f, 2.10f, amplitudeScale, wavelengthScale);
    field += waveContribution(x, z, normalize(float2(-0.35f, 0.94f)), 0.050f, 1.15f, 4.20f, amplitudeScale, wavelengthScale);
    field += waveContribution(x, z, normalize(float2(0.15f, 0.99f)), 0.025f, 0.72f, 1.30f, amplitudeScale, wavelengthScale);
    return field;
}

inline bool intersectOcean(float3 ro, float3 rd, float amplitudeScale, float wavelengthScale,
                           float maxT, thread SurfaceHit &hit) {
    if (abs(rd.y) < 1.0e-7f) return false;
    float t = (0.30f - ro.y) / rd.y;
    if (!isfinite(t) || t <= 1.0e-4f || t >= maxT) return false;

    float3 field = float3(0.0f);
    for (int iteration = 0; iteration < 10; ++iteration) {
        float3 point = ro + rd * t;
        field = oceanField(point.x, point.z, amplitudeScale, wavelengthScale);
        float error = point.y - (0.30f + field.x);
        float derivative = rd.y - field.y * rd.x - field.z * rd.z;
        if (!isfinite(derivative) || abs(derivative) < 1.0e-7f) return false;
        float delta = error / derivative;
        if (!isfinite(delta)) return false;
        float maxStep = max(2.0f, abs(t) * 0.45f);
        t -= clamp(delta, -maxStep, maxStep);
        if (!isfinite(t) || t <= 1.0e-4f || t >= maxT) return false;
        if (abs(delta) < 1.0e-6f) break;
    }

    float3 point = ro + rd * t;
    field = oceanField(point.x, point.z, amplitudeScale, wavelengthScale);
    if (abs(point.y - (0.30f + field.x)) > 0.0025f) return false;
    float3 normal = safeNormalize(float3(-field.y, 1.0f, -field.z));
    if (dot(normal, rd) > 0.0f) normal = -normal;
    hit.t = t;
    hit.point = point;
    hit.normal = normal;
    hit.uv = float2(0.0f);
    hit.triangleIndex = 0u;
    hit.found = true;
    hit.isWater = true;
    return true;
}

inline bool intersectScene(float3 ro, float3 rd,
                           const device GpuTriangle *triangles,
                           const device float4 *nodeBounds,
                           const device int4 *nodeMetadata,
                           uint nodeCount, thread SurfaceHit &hit) {
    hit.t = 1.0e30f;
    hit.found = false;
    if (nodeCount == 0) return false;
    int stack[64];
    int stackSize = 1;
    stack[0] = 0;
    uint visited = 0;
    while (stackSize > 0 && visited < nodeCount * 2u) {
        int nodeIndex = stack[--stackSize];
        if (nodeIndex < 0 || uint(nodeIndex) >= nodeCount) continue;
        ++visited;
        float3 bmin = nodeBounds[nodeIndex * 2].xyz;
        float3 bmax = nodeBounds[nodeIndex * 2 + 1].xyz;
        if (!intersectBox(ro, rd, bmin, bmax, hit.t)) continue;
        int4 meta = nodeMetadata[nodeIndex];
        if (meta.w > 0) {
            for (int i = 0; i < meta.w; ++i) {
                uint triangleIndex = uint(meta.z + i);
                (void)intersectTriangle(ro, rd, triangles[triangleIndex], hit.t, hit, triangleIndex);
            }
        } else {
            if (meta.y >= 0 && stackSize < 63) stack[stackSize++] = meta.y;
            if (meta.x >= 0 && stackSize < 63) stack[stackSize++] = meta.x;
        }
    }
    return hit.found;
}

inline float3 cosineHemisphere(float3 normal, thread uint &rng) {
    float r1 = random01(rng);
    float r2 = random01(rng);
    float phi = 6.28318530718f * r1;
    float radius = sqrt(r2);
    float3 helper = abs(normal.y) < 0.999f ? float3(0.0f, 1.0f, 0.0f) : float3(1.0f, 0.0f, 0.0f);
    float3 tangent = safeNormalize(cross(helper, normal));
    float3 bitangent = cross(normal, tangent);
    return safeNormalize(tangent * (cos(phi) * radius)
                       + normal * sqrt(max(0.0f, 1.0f - r2))
                       + bitangent * (sin(phi) * radius));
}

inline float smoothRange(float a, float b, float x) { return smoothstep(a, b, x); }

inline float3 skyColor(float3 direction, float timeOfDay, float3 sunDirection, float3 sunColor) {
    float y = clamp(safeNormalize(direction).y, -0.08f, 1.0f);
    float vertical = smoothstep(-0.08f, 0.72f, y);
    float daylight = smoothstep(5.0f, 8.4f, timeOfDay) * (1.0f - smoothstep(16.1f, 20.2f, timeOfDay));
    float sunrise = exp(-pow((timeOfDay - 6.2f) / 1.55f, 2.0f));
    float sunset = exp(-pow((timeOfDay - 18.2f) / 1.6f, 2.0f));
    float twilight = saturate1(max(sunrise, sunset));
    float3 dayHorizon = float3(0.58f, 0.75f, 0.94f);
    float3 dayZenith = float3(0.095f, 0.29f, 0.67f);
    float3 duskHorizon = float3(1.15f, 0.34f, 0.105f);
    float3 duskZenith = float3(0.17f, 0.20f, 0.39f);
    float3 nightHorizon = float3(0.012f, 0.019f, 0.040f);
    float3 nightZenith = float3(0.0025f, 0.006f, 0.022f);
    float3 day = mix(dayHorizon, dayZenith, vertical);
    float3 dusk = mix(duskHorizon, duskZenith, vertical);
    float3 night = mix(nightHorizon, nightZenith, vertical);
    float3 result = mix(mix(night, day, daylight), dusk, twilight * 0.88f);
    float sunDot = dot(safeNormalize(direction), safeNormalize(sunDirection));
    float halo = pow(max(0.0f, sunDot), 24.0f) * (0.16f + 0.55f * twilight);
    float disc = sunDot > 0.99945f ? pow((sunDot - 0.99945f) / 0.00055f, 2.0f) * (1.2f + 5.0f * daylight) : 0.0f;
    return result + sunColor * (halo + disc);
}

inline float hashNoise(float3 p) {
    p = fract(p * 0.1031f);
    p += dot(p, p.yzx + 33.33f);
    return fract((p.x + p.y) * p.z);
}
inline float valueNoise(float3 p) {
    float3 i = floor(p);
    float3 f = fract(p);
    f = f * f * (3.0f - 2.0f * f);
    float n000 = hashNoise(i + float3(0,0,0));
    float n100 = hashNoise(i + float3(1,0,0));
    float n010 = hashNoise(i + float3(0,1,0));
    float n110 = hashNoise(i + float3(1,1,0));
    float n001 = hashNoise(i + float3(0,0,1));
    float n101 = hashNoise(i + float3(1,0,1));
    float n011 = hashNoise(i + float3(0,1,1));
    float n111 = hashNoise(i + float3(1,1,1));
    float x00 = mix(n000, n100, f.x);
    float x10 = mix(n010, n110, f.x);
    float x01 = mix(n001, n101, f.x);
    float x11 = mix(n011, n111, f.x);
    return mix(mix(x00, x10, f.y), mix(x01, x11, f.y), f.z);
}
inline float cloudDensity(float3 p, float coverage) {
    float3 q = float3(p.x * 0.115f + 2.7f, p.y * 0.18f + 4.1f, p.z * 0.115f - 1.9f);
    float broad = 0.0f;
    float amp = 0.60f;
    float norm = 0.0f;
    for (int octave = 0; octave < 3; ++octave) {
        broad += valueNoise(q) * amp;
        norm += amp;
        q *= 2.03f;
        amp *= 0.5f;
    }
    broad /= max(norm, 1.0e-5f);
    float detail = valueNoise(q * 1.45f + float3(17.0f, 0.0f, -9.0f));
    float shape = broad * 0.82f + detail * 0.18f;
    float threshold = 0.78f - coverage * 0.47f;
    float vertical = smoothstep(0.0f, 0.18f, (p.y - 5.2f) / 4.0f)
                   * (1.0f - smoothstep(0.68f, 1.0f, (p.y - 5.2f) / 4.0f) * 0.38f);
    return smoothstep(threshold, threshold + 0.19f, shape) * vertical * 1.35f;
}
inline float3 cloudMarch(float3 ro, float3 rd, float3 background, float coverage,
                         float3 sunDirection, float3 sunColor, thread uint &rng) {
    if (coverage <= 0.001f || rd.y <= 0.015f) return background;
    float t0 = (5.2f - ro.y) / rd.y;
    float t1 = (9.2f - ro.y) / rd.y;
    if (t1 <= 0.0f) return background;
    float start = max(0.0f, t0);
    float end = max(start, t1);
    if (end <= start) return background;
    float stepSize = (end - start) / 12.0f;
    float jitter = random01(rng) * 0.8f + 0.1f;
    float transmittance = 1.0f;
    float3 accumulated = float3(0.0f);
    for (int i = 0; i < 12 && transmittance > 0.025f; ++i) {
        float t = start + (float(i) + jitter) * stepSize;
        float3 position = ro + rd * t;
        float density = cloudDensity(position, coverage);
        if (density <= 0.0f) continue;
        float sunFacing = 0.5f + 0.5f * max(0.0f, dot(rd, sunDirection));
        float powder = 1.0f - exp(-density * 1.6f);
        float3 cloudLight = mix(float3(0.24f, 0.29f, 0.37f), float3(0.88f, 0.91f, 0.94f), sunFacing)
                          + sunColor * (0.12f + 0.24f * sunFacing);
        float alpha = 1.0f - exp(-density * stepSize * 0.34f);
        accumulated += cloudLight * (transmittance * alpha * (0.78f + 0.22f * powder));
        transmittance *= (1.0f - alpha);
    }
    return accumulated + background * transmittance;
}

inline float3 tracePath(float3 rayOrigin, float3 rayDirection,
                        const device GpuTriangle *triangles,
                        const device float4 *nodeBounds,
                        const device int4 *nodeMetadata,
                        uint nodeCount,
                        texture2d_array<float, access::sample> diffuseTextures,
                        sampler textureSampler,
                        constant float *p,
                        thread uint &rng) {
    float3 radiance = float3(0.0f);
    float3 throughput = float3(1.0f);
    bool insideWater = false;
    float3 sunDirection = float3(p[24], p[25], p[26]);
    float3 sunColor = float3(p[27], p[28], p[29]);
    float sunIntensity = p[22];
    float timeOfDay = p[23];
    float cloudCoverage = p[21];

    for (int bounce = 0; bounce < 6; ++bounce) {
        SurfaceHit hit;
        bool found = intersectScene(rayOrigin, rayDirection, triangles, nodeBounds, nodeMetadata, nodeCount, hit);
        if (p[30] > 0.5f) {
            SurfaceHit waterHit;
            float maxT = found ? hit.t : 1.0e30f;
            if (intersectOcean(rayOrigin, rayDirection, p[31], p[32], maxT, waterHit)) {
                hit = waterHit;
                found = true;
            }
        }
        if (!found) {
            float3 environment = skyColor(rayDirection, timeOfDay, sunDirection, sunColor);
            environment = cloudMarch(rayOrigin, rayDirection, environment, cloudCoverage, sunDirection, sunColor, rng);
            if (insideWater) environment *= exp(float3(-0.42f, -0.13f, -0.045f) * 24.0f);
            radiance += throughput * environment;
            break;
        }

        if (insideWater) throughput *= exp(float3(-0.42f, -0.13f, -0.045f) * hit.t);
        float3 normal = hit.normal;
        float3 view = -rayDirection;

        if (hit.isWater) {
            float nFrom = insideWater ? 1.333f : 1.0f;
            float nTo = insideWater ? 1.0f : 1.333f;
            float eta = nFrom / nTo;
            float cosI = clamp(-dot(rayDirection, normal), 0.0f, 1.0f);
            float sinT2 = eta * eta * max(0.0f, 1.0f - cosI * cosI);
            float r0 = (nFrom - nTo) / (nFrom + nTo);
            r0 *= r0;
            float fresnel = r0 + (1.0f - r0) * pow(1.0f - cosI, 5.0f);
            float3 transmitted = refract(rayDirection, normal, eta);
            if (sinT2 >= 1.0f || dot(transmitted, transmitted) < 1.0e-8f || random01(rng) < fresnel) {
                rayDirection = safeNormalize(reflect(rayDirection, normal));
                rayOrigin = hit.point + normal * 0.0018f;
            } else {
                rayDirection = safeNormalize(transmitted);
                insideWater = !insideWater;
                rayOrigin = hit.point - normal * 0.0018f;
            }
            continue;
        }

        const device GpuTriangle &material = triangles[hit.triangleIndex];
        float roughness = clamp(material.uv2meta.w, 0.035f, 1.0f);
        radiance += throughput * material.emission.xyz;
        float3 albedo = material.albedo.xyz;
        int textureIndex = int(material.uv2meta.z);
        if (material.extra.y > 0.5f && textureIndex >= 0) {
            float2 uv = float2(fract(hit.uv.x), 1.0f - fract(hit.uv.y));
            float3 texel = diffuseTextures.sample(textureSampler, uv, uint(textureIndex)).rgb;
            albedo *= texel;
        }

        float3 ambient = skyColor(normal, timeOfDay, sunDirection, sunColor) * 0.16f;
        radiance += throughput * albedo * ambient;

        float nDotSun = max(0.0f, dot(normal, sunDirection));
        if (nDotSun > 0.0f && sunIntensity > 0.001f) {
            SurfaceHit blocker;
            bool occluded = intersectScene(hit.point + normal * 0.0018f, sunDirection,
                                            triangles, nodeBounds, nodeMetadata, nodeCount, blocker);
            if (!occluded) {
                float3 halfway = safeNormalize(sunDirection + view);
                float gloss = pow(max(0.0f, dot(normal, halfway)), max(3.0f, 2.0f / (roughness * roughness)));
                float3 brdf = albedo * (1.0f / 3.14159265359f) + material.specular.xyz * (gloss * 0.22f);
                radiance += throughput * brdf * sunColor * (nDotSun * sunIntensity);
            }
        }

        // Two inexpensive point-fill sources retain the original scene's warm/cool fill balance.
        const float3 fillPositions[2] = { float3(-4.8f, 7.2f, 4.5f), float3(4.5f, 4.0f, -3.5f) };
        const float3 fillColors[2] = { float3(1.0f, 0.88f, 0.76f), float3(0.54f, 0.70f, 1.0f) };
        const float fillPower[2] = { 120.0f, 58.0f };
        for (int light = 0; light < 2; ++light) {
            float3 delta = fillPositions[light] - hit.point;
            float distance2 = max(1.0f, dot(delta, delta));
            float distance = sqrt(distance2);
            float3 lightDirection = delta / distance;
            float ndotl = max(0.0f, dot(normal, lightDirection));
            if (ndotl > 0.0f) {
                SurfaceHit blocker;
                bool occluded = intersectScene(hit.point + normal * 0.0015f, lightDirection,
                                                triangles, nodeBounds, nodeMetadata, nodeCount, blocker)
                                && blocker.t < distance - 0.003f;
                if (!occluded) {
                    float3 halfway = safeNormalize(lightDirection + view);
                    float gloss = pow(max(0.0f, dot(normal, halfway)), max(3.0f, 2.0f / (roughness * roughness)));
                    float3 brdf = albedo * (1.0f / 3.14159265359f) + material.specular.xyz * (gloss * 0.22f);
                    radiance += throughput * brdf * fillColors[light] * (ndotl * fillPower[light] / distance2);
                }
            }
        }

        float specularProbability = clamp(max(material.specular.x, max(material.specular.y, material.specular.z)) * 0.68f, 0.0f, 0.78f);
        float diffuseProbability = max(0.22f, 1.0f - specularProbability);
        if (random01(rng) < specularProbability) {
            float3 reflected = safeNormalize(reflect(rayDirection, normal));
            float3 rough = cosineHemisphere(reflected, rng);
            rayDirection = safeNormalize(mix(reflected, rough, roughness * 0.72f));
            if (dot(rayDirection, normal) <= 0.0f) rayDirection = reflected;
            throughput *= max(material.specular.xyz, float3(0.03f)) / max(0.05f, specularProbability);
        } else {
            rayDirection = cosineHemisphere(normal, rng);
            throughput *= albedo / diffuseProbability;
        }
        if (bounce >= 2) {
            float survive = clamp(max(throughput.x, max(throughput.y, throughput.z)), 0.12f, 0.88f);
            if (random01(rng) > survive) break;
            throughput /= survive;
        }
        rayOrigin = hit.point + normal * 0.0015f;
    }
    return radiance;
}

kernel void metalPathTraceKernel(
    const device GpuTriangle *triangles [[buffer(0)]],
    const device float4 *nodeBounds [[buffer(1)]],
    const device int4 *nodeMetadata [[buffer(2)]],
    device uint *outputPixels [[buffer(3)]],
    constant float *p [[buffer(4)]],
    texture2d_array<float, access::sample> diffuseTextures [[texture(0)]],
    sampler textureSampler [[sampler(0)]],
    uint2 gid [[thread_position_in_grid]]) {
    uint width = uint(p[0]);
    uint height = uint(p[1]);
    uint spp = uint(p[2]);
    uint yStart = uint(p[3]);
    uint rows = uint(p[4]);
    uint nodeCount = uint(p[6]);
    if (gid.x >= width || gid.y >= rows || yStart + gid.y >= height) return;

    float3 cameraPosition = float3(p[8], p[9], p[10]);
    float3 cameraForward = float3(p[11], p[12], p[13]);
    float3 cameraRight = float3(p[14], p[15], p[16]);
    float3 cameraUp = float3(p[17], p[18], p[19]);
    float tanHalfFov = p[20];
    float aspect = float(width) / float(height);
    uint absoluteY = yStart + gid.y;
    float3 accumulated = float3(0.0f);

    for (uint sampleIndex = 0; sampleIndex < spp; ++sampleIndex) {
        uint rng = seedFor(gid.x, absoluteY, sampleIndex);
        float jx = random01(rng) - 0.5f;
        float jy = random01(rng) - 0.5f;
        float sx = (2.0f * ((float(gid.x) + jx + 0.5f) / float(width)) - 1.0f) * aspect * tanHalfFov;
        float sy = (1.0f - 2.0f * ((float(absoluteY) + jy + 0.5f) / float(height))) * tanHalfFov;
        float3 direction = safeNormalize(cameraForward + cameraRight * sx + cameraUp * sy);
        accumulated += tracePath(cameraPosition, direction, triangles, nodeBounds, nodeMetadata, nodeCount,
                                 diffuseTextures, textureSampler, p, rng);
    }

    float3 linearColor = max(accumulated / float(max(1u, spp)), float3(0.0f));
    float3 mapped = linearColor / (1.0f + linearColor);
    float3 displayColor = pow(clamp(mapped, 0.0f, 1.0f), float3(1.0f / 2.2f));
    uint3 rgb = uint3(clamp(displayColor * 255.0f, 0.0f, 255.0f));
    outputPixels[gid.y * width + gid.x] = 0xff000000u | (rgb.r << 16) | (rgb.g << 8) | rgb.b;
}
