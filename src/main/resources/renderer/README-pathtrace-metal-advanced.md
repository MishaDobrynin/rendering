# Technical guide: `src/main/resources/renderer/PathTrace.metal`

This document describes the Metal Shading Language source used by Warship Studio's native GPU renderer. It focuses on the actual algorithms and data flow in the shader, including the assumptions made by the Java scene packer and the Objective-C bridge.

The shader is a compute kernel, not a graphics-pipeline vertex/fragment shader. It has no rasterization stage. GPU threads directly calculate output pixels by generating camera rays and tracing them through the scene.

---

## 1. Kernel overview

The entry point is:

```metal
kernel void metalPathTraceKernel(...)
```

The Objective-C bridge compiles the source into a Metal library, obtains this function by name, creates an `MTLComputePipelineState`, binds its resources, and dispatches a 2D grid of threads.

One valid thread calculates one output pixel for the current row band:

1. Read the image and band dimensions from the uniform buffer.
2. Generate a deterministic random-number state for this pixel and sample.
3. Jitter the sample location inside the pixel.
4. Construct a camera ray from the camera basis vectors.
5. Call `tracePath` for each requested sample.
6. Average the sample radiance.
7. Apply tone mapping and gamma conversion.
8. Pack the result into a 32-bit integer and write it to the output buffer.

Threads do not share a pixel accumulator. A thread owns its output index and writes that index once after finishing all samples.

## 2. MSL types and address spaces

Metal Shading Language is based on C++14 with Metal-specific types, attributes, and resource rules.

The shader uses vector types such as `float2`, `float3`, and `float4`. These are built-in vector types, not arrays. Components can be accessed with `.x`, `.y`, `.z`, `.w`, or swizzles such as `.xyz`.

The shader also uses address-space qualifiers:

- `device`: memory in a GPU resource such as an `MTLBuffer`. It is accessible to GPU threads and is not private to one thread.
- `constant`: read-only data supplied for a dispatch. Here it is the uniform parameter buffer.
- `thread`: storage local to one GPU thread. Function parameters such as `thread SurfaceHit &hit` refer to that thread's local value.
- `texture`: a texture resource accessed through Metal's texture interface.

Address spaces are part of the type system. A pointer to `device GpuTriangle` is not interchangeable with an ordinary thread-local pointer.

The `[[buffer(n)]]`, `[[texture(n)]]`, and `[[sampler(n)]]` attributes specify the resource binding indices used by the Objective-C encoder.

## 3. `GpuTriangle`: the CPU/GPU record contract

```metal
struct GpuTriangle {
    float4 p0, p1, p2;
    float4 n0, n1, n2;
    float4 uv01;
    float4 uv2meta;
    float4 albedo;
    float4 specular;
    float4 emission;
    float4 extra;
};
```

Each triangle occupies 12 `float4` values:

```text
12 × 4 floats × 4 bytes = 192 bytes
```

The Java packer represents this as 48 consecutive floats. The Objective-C bridge checks that the total float count is divisible by 48 before allocating the Metal buffer.

| Field | Shader interpretation |
|---|---|
| `p0`, `p1`, `p2` | Triangle vertex positions |
| `n0`, `n1`, `n2` | Vertex normals |
| `uv01.xy` | UV coordinate at vertex 0 |
| `uv01.zw` | UV coordinate at vertex 1 |
| `uv2meta.xy` | UV coordinate at vertex 2 |
| `uv2meta.z` | Diffuse texture-array layer |
| `uv2meta.w` | Material roughness |
| `albedo.xyz` | Base material color |
| `specular.xyz` | Specular material parameter |
| `emission.xyz` | Emissive radiance contribution |
| `extra.y` | Flag indicating that the triangle has texture/UV data |

The fourth components of position and normal vectors are not used by the intersection code shown here. `float4` is used to make the record layout predictable and aligned.

This layout is an ABI between Java and MSL. Changing field order or record stride in only one language will make the shader interpret unrelated values as positions, UVs, or material parameters.

## 4. `SurfaceHit`: intersection result

`SurfaceHit` stores the result of the nearest intersection found by a ray query:

- `t`: distance parameter along the ray;
- `point`: world-space hit position;
- `normal`: shading or surface normal;
- `uv`: interpolated texture coordinate;
- `triangleIndex`: index into the triangle buffer;
- `found`: whether a hit exists;
- `isWater`: whether this hit represents the analytic ocean rather than a mesh triangle.

The ray is represented parametrically:

\[
P(t) = O + tD
\]

where `O` is the ray origin, `D` is its direction, and `t` is the distance parameter. The shader normalizes most ray directions, so `t` is approximately a world-space distance.

The same structure is used for triangle and water intersections so that `tracePath` can compare their `t` values and choose the nearer surface.

## 5. Small numerical helpers

### 5.1 `saturate1`

Clamps a scalar to the interval `[0,1]`. It is used for quantities such as coverage and interpolation weights that must remain bounded.

### 5.2 `safeNormalize`

Calculates the squared length with `dot(v,v)`. If the vector is large enough, it multiplies by `rsqrt(len2)`, which is the reciprocal square root. Otherwise it returns an upward vector.

The zero-length guard prevents division by zero and NaNs from propagating through later lighting calculations.

### 5.3 Random state

`seedFor(x,y,sampleIndex)` hashes the pixel coordinates and sample number into a nonzero 32-bit seed.

`random01` updates the seed using xorshift operations:

```text
state ^= state << 13
state ^= state >> 17
state ^= state << 5
```

It then maps the integer state to a floating-point value near `[0,1)`.

This is a compact per-thread pseudorandom generator. It is not a cryptographic generator. Its purpose is to provide inexpensive, repeatable sampling for pixels. Since the seed depends on pixel coordinates and sample index, the same pixel/sample pair produces the same sequence when the scene and code are unchanged.

## 6. Ray/AABB intersection: `intersectBox`

The BVH stores axis-aligned bounding boxes (AABBs). Each box is represented by a minimum corner and maximum corner.

`intersectBox` implements the slab method. For each axis:

1. Consider the two planes at the box's minimum and maximum coordinate.
2. Calculate the ray parameter where it intersects each plane.
3. Order those two parameters as entry and exit.
4. Intersect the valid interval with the intervals from the other axes.

The function starts with:

```text
tNear = 1e-4
tFar  = maxT
```

The small positive lower bound rejects intersections extremely close to the ray origin. `maxT` is the current nearest known hit distance, so a box farther away than an existing hit can be rejected.

If a ray direction component is nearly zero, the ray is treated as parallel to the corresponding pair of planes. It can only intersect the box if its origin lies between that axis's minimum and maximum coordinates.

The box test does not return an exact intersection point. It only answers whether the ray could intersect geometry inside the box. Triangle tests are required for an actual surface hit.

## 7. Ray/triangle intersection: `intersectTriangle`

This function implements the Möller–Trumbore algorithm.

Let the triangle vertices be \(A\), \(B\), and \(C\). Define:

\[
E_1 = B-A,\qquad E_2=C-A
\]

The ray is \(O+tD\). The algorithm solves for the ray parameter \(t\) and the two barycentric coordinates \(u,v\).

The implementation performs these steps:

1. Calculate `p = cross(rd, e2)`.
2. Calculate the determinant `det = dot(e1,p)`.
3. Reject if the determinant is close to zero, because the ray is nearly parallel to the triangle plane or the triangle is degenerate.
4. Calculate `u`; reject if it is outside `[0,1]`.
5. Calculate `v`; reject if it is negative or if `u + v > 1`.
6. Calculate `t`; reject if it is behind/too close to the ray or beyond `maxT`.

The third barycentric weight is:

\[
w=1-u-v
\]

These weights are used to interpolate attributes stored at vertices.

### 7.1 Interpolated normal

\[
N = \operatorname{normalize}(wN_0+uN_1+vN_2)
\]

This supports smooth shading when the vertex normals differ. If the interpolated normal points in the same direction as the incoming ray, the code negates it so that the normal faces against the ray.

This is a shading normal. It may differ from the geometric face normal.

### 7.2 Interpolated UV coordinate

\[
UV = wUV_0+uUV_1+vUV_2
\]

The resulting UV is later used to sample the diffuse texture array.

## 8. BVH traversal: `intersectScene`

The shader uses a bounding volume hierarchy to avoid testing every triangle for every ray.

The BVH is stored in two parallel arrays:

- `nodeBounds`: two `float4` values per node. Their `.xyz` components contain the minimum and maximum corners.
- `nodeMetadata`: one `int4` per node.

For an internal node, metadata `.x` and `.y` are child node indices. For a leaf, `.z` is the first triangle index and `.w` is the number of triangles in that leaf. A positive `.w` identifies a leaf.

### 8.1 Traversal procedure

The function uses a local integer stack:

```metal
int stack[64];
```

It starts with node zero. For each popped node:

1. Reject invalid node indices.
2. Increment the visit counter.
3. Test the node's AABB against the ray, using the nearest hit distance found so far.
4. If the box misses, skip the node.
5. If the node is a leaf, test its triangle range.
6. Otherwise, push its valid child indices.

When a triangle hit is found, `hit.t` is reduced. Later AABB and triangle tests can then reject intersections farther away.

### 8.2 Traversal limits

The stack has 64 entries. The code only pushes a child when `stackSize < 63`, and the loop also limits visits to `nodeCount * 2`.

These are defensive execution bounds for GPU code. They prevent unbounded local traversal, but they impose a requirement on the BVH builder: the hierarchy must be shallow enough that the traversal stack does not overflow. If a hierarchy exceeds the supported stack depth, a child can be omitted and geometry may be missed.

The traversal does not sort children by near distance. It visits them in the order they are pushed. The closest-hit distance still prunes farther boxes, but a near-first traversal could improve pruning efficiency.

## 9. Cosine-weighted hemisphere sampling

`cosineHemisphere(normal, rng)` generates a random direction above a surface normal with a cosine-weighted distribution.

The routine first generates two uniform random values, then maps them to a disk using:

\[
r=\sqrt{r_2},\qquad \phi=2\pi r_1
\]

It constructs a local tangent and bitangent around the supplied normal, then transforms the disk sample into a hemisphere direction.

The cosine-weighted distribution is useful for diffuse reflection because a Lambertian surface's outgoing contribution is proportional to the cosine of the angle from the normal. Sampling directions with this distribution places more samples near the normal and fewer near the horizon.

## 10. Analytic ocean

The ocean is represented by a continuous height function, not by a finite triangle mesh. The shader tests rays against the function directly.

### 10.1 `waveContribution`

For one directional wave, the shader calculates:

\[
k=\frac{2\pi}{L}
\]

\[
\theta=k(d\cdot(x,z))+\phi
\]

\[
h=A\sin(\theta)
\]

where:

- \(A\) is amplitude;
- \(L\) is wavelength;
- \(d\) is a normalized horizontal direction;
- \(\phi\) is phase;
- \(k\) is wave number.

The function returns three values:

- height contribution \(A\sin(\theta)\);
- derivative of height with respect to X;
- derivative of height with respect to Z.

The derivatives are:

\[
\frac{\partial h}{\partial x}=Ak\cos(\theta)d_x
\]

\[
\frac{\partial h}{\partial z}=Ak\cos(\theta)d_z
\]

### 10.2 `oceanField`

This function sums four waves with different directions, amplitudes, wavelengths, and phases. It returns:

```text
field.x = total height offset
field.y = derivative with respect to world X
field.z = derivative with respect to world Z
```

The base water height is `0.30`. The wave amplitude and wavelength scales come from the uniform array.

### 10.3 `intersectOcean`

The function solves for the ray parameter at which:

\[
O_y+tD_y=0.30+h(O_x+tD_x,\ O_z+tD_z)
\]

It first intersects the ray with the mean water plane. That gives an initial estimate for \(t\). It then applies up to ten Newton iterations.

Define the error:

\[
F(t)=O_y+tD_y-0.30-h(O_x+tD_x,\ O_z+tD_z)
\]

Its derivative is:

\[
F'(t)=D_y-h_xD_x-h_zD_z
\]

The update is:

\[
t_{\text{new}}=t-\frac{F(t)}{F'(t)}
\]

The code rejects unstable derivatives, non-finite values, and steps outside the valid hit interval. Each Newton step is clamped to limit large changes. After iteration, it checks that the remaining height error is no more than `0.0025`.

The surface normal follows from the height-field gradient:

\[
N=\operatorname{normalize}(-h_x,1,-h_z)
\]

This normal is flipped if it points along the incoming ray.

This method gives an effectively unbounded mathematical surface in X/Z. It does not create an infinite GPU allocation; it evaluates the height function only where a ray query needs it.

## 11. Sky model: `skyColor`

The sky function is procedural and analytic. It does not simulate wavelength-dependent atmospheric scattering.

It uses the normalized view direction's Y component to interpolate between horizon and zenith colors. Three palettes are defined:

- day;
- dusk/twilight;
- night.

The `timeOfDay` value controls a daylight factor using smooth transitions around sunrise and sunset. Two exponential functions centered near 06:12 and 18:12 create sunrise and sunset influence.

The direction toward the sun is compared with the view direction using a dot product. A broad power function creates a halo, and a tighter threshold creates a sun disc.

The sun's RGB color scales these contributions. The returned sky value can exceed 1.0; tone mapping occurs later in the kernel.

## 12. Procedural clouds

The cloud implementation constructs a scalar density field and integrates it along upward-facing rays.

### 12.1 `hashNoise`

`hashNoise` maps a 3D coordinate to a pseudo-random scalar. It uses fractional arithmetic, a coordinate permutation, a dot product, and another fractional operation.

The function is deterministic: the same input coordinate returns the same value.

### 12.2 `valueNoise`

`valueNoise` evaluates the eight lattice corners surrounding a 3D point. It applies the cubic smoothing expression:

\[
f'=f^2(3-2f)
\]

It then linearly interpolates the corner values along X, Y, and Z. The result changes smoothly between lattice points.

### 12.3 `cloudDensity`

The input position is scaled and offset before sampling noise. Three octaves are accumulated:

- the first octave has amplitude 0.60;
- each subsequent octave doubles the spatial frequency and halves the amplitude.

The accumulated value is normalized by the sum of octave amplitudes. A separate higher-frequency noise sample adds detail.

Coverage changes the density threshold:

\[
threshold=0.78-0.47\cdot coverage
\]

The `smoothstep` around that threshold converts the noise value into a soft density boundary. A vertical shaping function reduces density near the bottom and top of the cloud layer.

The function returns a density-like scalar, not a physically calibrated mass density.

### 12.4 `cloudMarch`

Clouds occupy an approximate vertical layer from Y = 5.2 to Y = 9.2. The function only evaluates rays with a sufficiently positive Y direction.

It calculates the ray's interval through that layer and divides it into 12 equal steps. A random offset moves the sample locations within the steps to reduce visible banding.

For each sample:

1. Calculate the world-space sample point.
2. Evaluate `cloudDensity`.
3. Skip the sample if density is zero.
4. Estimate cloud illumination from the view direction and sun direction.
5. Convert density and step length into an opacity value.
6. Add the sample's premultiplied color, weighted by current transmittance.
7. Reduce transmittance by the sample opacity.

The final composite is:

\[
C=C_{\text{cloud}}+T_{\text{remaining}}C_{\text{background}}
\]

The loop stops early when remaining transmittance is low.

This is a low-cost volumetric approximation. It does not calculate a separate light-ray integral through the cloud at each sample, and it does not solve multiple scattering. The cloud illumination is an approximation based on view/sun orientation.

## 13. `tracePath`: surface interaction and radiance accumulation

`tracePath` performs the main ray-path loop. It maintains:

- `radiance`: accumulated light contribution for the camera sample;
- `throughput`: the multiplicative contribution carried by the current path;
- `rayOrigin`: origin of the next ray;
- `rayDirection`: direction of the next ray;
- `insideWater`: whether the path is currently considered to be in water.

The loop runs for at most six surface interactions.

### 13.1 Find the nearest scene or water hit

First, `intersectScene` finds the nearest triangle hit. If the ocean flag is enabled, `intersectOcean` is called with the current triangle hit distance as its maximum distance.

The water hit replaces the triangle hit only if it is closer. The result is therefore the nearest of the triangle geometry and the analytic water surface.

### 13.2 Environment miss

If there is no hit, the shader evaluates the sky and cloud functions. If the ray is marked as underwater, it applies a fixed RGB attenuation factor for a nominal 24-unit distance. The environment is multiplied by `throughput`, added to `radiance`, and the path ends.

### 13.3 Underwater attenuation

When a path is inside water and reaches another hit, the throughput is multiplied by:

\[
\exp((-0.42,-0.13,-0.045)\cdot t)
\]

where the exponential is component-wise and \(t\) is the segment length parameter.

The three channels have different attenuation coefficients. Red is attenuated most strongly and blue least strongly, approximating the blue/green appearance of light traveling through water.

This is a simplified homogeneous-medium absorption model. It does not model scattering, turbidity variation, or wavelength-dependent spectral transport.

### 13.4 Water reflection and refraction

At a water hit, the shader selects refractive indices:

- air: 1.0;
- water: 1.333.

The relative index is:

\[
\eta=\frac{n_{\text{from}}}{n_{\text{to}}}
\]

The shader calculates the transmitted-angle condition and uses Schlick's approximation for Fresnel reflectance:

\[
F=F_0+(1-F_0)(1-\cos\theta)^5
\]

where:

\[
F_0=\left(\frac{n_{\text{from}}-n_{\text{to}}}
{n_{\text{from}}+n_{\text{to}}}\right)^2
\]

A random value chooses reflection with probability `F`. Total internal reflection or a degenerate refracted vector also forces reflection. Otherwise, the ray is refracted and `insideWater` is toggled.

The origin is offset slightly along or against the surface normal after the event. This is a ray-origin bias intended to avoid immediately intersecting the same surface again due to floating-point precision.

The Fresnel branch is stochastic. The code does not trace both reflected and refracted rays at every water hit.

### 13.5 Material and texture lookup

For a triangle hit, the triangle index selects the corresponding `GpuTriangle` record.

The shader reads roughness, albedo, specular data, and emission. Emission is added to radiance multiplied by the current throughput.

If the texture flag is enabled and the texture layer is nonnegative:

1. Wrap U using `fract`.
2. Wrap V and invert it using `1.0 - fract(v)`.
3. Sample the selected 2D texture-array layer with the bound sampler.
4. Multiply the sampled RGB by the material albedo.

The texture array is an sRGB Metal texture. Its layer index is stored in `uv2meta.z`. The shader assumes the Java packer assigned valid layer indices and that all layers have the dimensions expected by the native bridge.

### 13.6 Ambient contribution

The shader evaluates `skyColor` in the surface-normal direction and scales it by 0.16. It multiplies this ambient approximation by albedo and current throughput.

This is not a full indirect-light integral. It is a directional environment-color approximation used as an inexpensive ambient term.

### 13.7 Directional sun and shadow ray

The sun contribution is considered when the surface normal faces the sun and sun intensity is above a small threshold.

The shader traces a ray from a small offset above the hit point in the sun direction. If `intersectScene` finds no triangle, the sun is considered visible.

It then calculates:

- a diffuse term proportional to albedo divided by π;
- a specular term using the halfway vector between the light and view directions;
- a roughness-dependent exponent;
- multiplication by sun color, cosine of the light angle, and sun intensity.

The shadow query tests triangle geometry. The ocean is not included in this particular shadow test.

### 13.8 Two point-fill lights

Two additional lights are hard-coded in the shader. Each has a fixed world-space position, RGB color, and power.

For each light, the shader calculates the vector and squared distance from the hit point. It evaluates the normal/light cosine and traces a shadow ray. A blocker only counts if it is closer than the light.

The unoccluded contribution uses inverse-square attenuation:

\[
I \propto \frac{P}{r^2}
\]

The same diffuse-plus-specular approximation is used as for the sun.

These two lights are not supplied by Java and are not configurable through the uniform block in this version. They are fixed shader constants.

### 13.9 Selecting the next surface direction

After direct lighting is accumulated, the shader chooses between a specular-like direction and a diffuse direction.

The specular selection probability is derived from the maximum specular color component and clamped to 0.78. The diffuse probability is the remaining probability with a minimum of 0.22.

For the specular branch:

- calculate perfect reflection;
- generate a cosine-weighted direction around that reflected direction;
- blend toward the rough direction according to roughness;
- fall back to perfect reflection if the result lies below the surface.

For the diffuse branch, sample a cosine-weighted hemisphere around the surface normal.

The throughput is multiplied by the selected material contribution and divided by the branch probability. This compensates for selecting only one of the two branches in a stochastic sample.

This is a compact heuristic material model. It should not be assumed to be a complete energy-conserving microfacet BRDF or a fully unbiased physically based path integrator.

### 13.10 Russian roulette

Starting at bounce index 2, the shader may terminate the path probabilistically.

The survival probability is based on the largest throughput channel and clamped to `[0.12, 0.88]`.

If the random value exceeds the survival probability, the path terminates. Otherwise, throughput is divided by the survival probability.

This reduces the average amount of work spent on long paths. Dividing surviving paths by their survival probability compensates for the probability of termination in the estimator.

## 14. Kernel resource interface

The kernel signature binds these resources:

| Binding | Argument | Contents |
|---|---|---|
| `buffer(0)` | `triangles` | Array of `GpuTriangle` records |
| `buffer(1)` | `nodeBounds` | Two `float4` values per BVH node |
| `buffer(2)` | `nodeMetadata` | One `int4` per BVH node |
| `buffer(3)` | `outputPixels` | Packed 32-bit pixel output |
| `buffer(4)` | `p` | 33-float uniform block |
| `texture(0)` | `diffuseTextures` | 2D texture array |
| `sampler(0)` | `textureSampler` | Filtering and address configuration |

These indices must match the calls to `setBuffer`, `setTexture`, and `setSamplerState` in `metal_bridge.m`.

### 14.1 Uniform parameter block

The native bridge fills the 33 floats as follows:

| Indices | Meaning |
|---|---|
| `p[0]` | Full image width |
| `p[1]` | Full image height |
| `p[2]` | Samples per pixel |
| `p[3]` | First row of current band |
| `p[4]` | Number of rows in band |
| `p[5]` | Triangle count |
| `p[6]` | BVH node count |
| `p[7]` | Texture-layer count |
| `p[8..10]` | Camera position |
| `p[11..13]` | Camera forward vector |
| `p[14..16]` | Camera right vector |
| `p[17..19]` | Camera up vector |
| `p[20]` | Tangent of half the vertical field of view |
| `p[21]` | Cloud coverage |
| `p[22]` | Sun intensity |
| `p[23]` | Time of day |
| `p[24..26]` | Sun direction |
| `p[27..29]` | Sun RGB color |
| `p[30]` | Ocean enabled flag |
| `p[31]` | Ocean amplitude scale |
| `p[32]` | Ocean wavelength scale |

This is a positional interface. The shader does not receive field names or a self-describing structure. If the Java array order changes, the shader will read incorrect values without a type error.

## 15. Camera-ray construction and pixel indexing

`gid` is the thread's two-dimensional position in the dispatched grid.

- `gid.x` is the pixel's X coordinate.
- `gid.y` is the row index within the current band.

The absolute image row is:

```text
absoluteY = yStart + gid.y
```

The kernel checks the image and band bounds because the dispatch dimensions are rounded up to complete threadgroups.

The camera basis is read from the uniform block:

- camera position;
- forward;
- right;
- up.

The pixel coordinate is normalized to a range around `[-1,1]`. Horizontal position is multiplied by the aspect ratio and `tanHalfFov`; vertical position is multiplied by `tanHalfFov`.

The ray direction is:

```text
normalize(cameraForward
        + cameraRight * sx
        + cameraUp * sy)
```

This is a pinhole-camera model. It does not simulate depth of field or lens distortion.

## 16. Samples per pixel and accumulation

The kernel loops from zero to `spp - 1`. For each sample it creates a random state from `(x, absoluteY, sampleIndex)`, generates two subpixel offsets, constructs the ray, and calls `tracePath`.

The radiance values are summed and divided by the sample count:

\[
C_{\text{mean}}=\frac{1}{N}\sum_{i=1}^{N}C_i
\]

This reduces stochastic noise as sample count increases. The shader computes all samples for a pixel during one dispatch; it does not preserve an accumulation buffer between separate dispatches.

## 17. Tone mapping, gamma and packed output

The accumulated result is clamped to nonnegative linear RGB and passed through a Reinhard-style mapping:

\[
C_{\text{mapped}}=\frac{C_{\text{linear}}}{1+C_{\text{linear}}}
\]

This compresses large values into the display range while preserving their ordering.

The shader then applies:

\[
C_{\text{display}}=C_{\text{mapped}}^{1/2.2}
\]

It converts each channel to an integer from 0 to 255 and packs the result:

```text
0xFF000000 | (R << 16) | (G << 8) | B
```

The alpha byte is set to 255. The output buffer therefore contains display-referred 8-bit RGB values in a 32-bit integer, not linear HDR radiance.

The Objective-C bridge copies these integers into a Java `int[]`. Java performs the PNG encoding.

## 18. Current algorithmic limits

These are properties of the implementation, not general limitations of Metal:

- **Maximum path length:** six surface interactions.
- **BVH stack:** 64 local integer entries; the BVH must remain within the supported traversal depth.
- **Cloud integration:** 12 fixed samples through a single approximate cloud layer.
- **Ocean:** four sinusoidal components and at most ten Newton iterations per ray intersection.
- **Sky:** analytic day/dusk/night palettes, not atmospheric scattering.
- **Lighting:** one directional sun plus two hard-coded point-fill lights.
- **Shadow rays:** triangle BVH queries; the sun shadow query does not test the ocean.
- **Material model:** heuristic diffuse/specular branch selection, not a complete microfacet implementation.
- **Texture filtering:** one mip level with linear filtering and repeat addressing.
- **Output:** tone-mapped and gamma-converted 8-bit values; no HDR output buffer.
- **Progressive rendering:** no accumulation across dispatches.
- **Camera:** pinhole projection; no depth of field.

These details matter when evaluating image quality or planning extensions. For example, adding configurable scene lights requires changing the Java-to-native uniform/resource interface, not merely adding a new UI control. Adding mipmapping requires changes to texture creation and sampling. Supporting deeper BVHs requires changing the traversal strategy and validating its stack behavior.
