## Main features

- **Preview:** software triangle rasterizer with a depth buffer, perspective-correct UV sampling, diffuse textures and a quick ocean preview.
- **Final render:** CPU path tracer with a BVH, area lights, soft shadows, diffuse/specular bounces, Fresnel reflection/refraction and a sunlit sky.
- **Large mesh import:** streaming OBJ parser. The BVH is built in-place over a triangle array rather than repeatedly copying sub-lists.
- **Materials:** OBJ/MTL `Kd`, `Ks`, `Ke`, `Ns` and `map_Kd` diffuse texture maps.
- **Ocean:** generated tessellated Gerstner-wave geometry (not a flat plane with a wave-colored shader), analytic wave normals, water IOR 1.333, Fresnel reflection/refraction, underwater absorption and a dark seabed for refracted rays.
- **Long renders:** 4–512 samples per pixel, output sizes from 640×360 through 3840×2160, progress and elapsed-time display, cancellation, PNG export.
- **Camera:** left-drag to orbit and mouse wheel to zoom.

## Requirements

- Java 17 or newer (OpenJDK 17 recommended)
The renderer is CPU-based.

## Build and run

```bash
sudo apt update
sudo apt install openjdk-17-jdk
```

Build and launch:

```bash
./build.sh
./run.sh
```

`run.sh` lets the JVM size its heap to approximately 70% of the available memory. 

## Water implementation

Ocean mode adds a separate, explicitly tessellated water mesh: 190×190 quads, or 72,200 triangles, generated from four directional Gerstner wave components. 
The path tracer treats the surface as a dielectric boundary, samples Fresnel reflection versus Snell-law refraction, and attenuates rays traveling through the water using wavelength-dependent absorption. 
Dark seabed is included so transmitted rays can illuminate underwater geometry rather than simply returning a blue background.

The raster preview uses a faster view-angle/specular approximation over the same actual wave geometry. The final ray-traced mode performs the water reflection/refraction calculation. 

## Project layout

```text
src/main/java/renderer/
  Main.java          Swing UI, model loading, environment, controls, PNG export
  MeshLoader.java    Format dispatch
  ObjLoader.java     Streaming OBJ/MTL and UV diffuse textures
  OceanWater.java    Tessellated Gerstner-wave geometry
  RasterRenderer.java
  PathTracer.java    CPU path tracing and dielectric water
  Bvh.java           In-place median BVH
  Scene.java
  Triangle.java
  Material.java
  Camera.java
  Vec3.java
```


## Interactive controls
- During camera orbit/zoom, the viewport refreshes after a short pause using a reduced 240–360 px raster preview. The full-resolution raster preview and raytrace remain available from the toolbar.
- **Wave height** adjusts Gerstner amplitude; **Wave length** adjusts wave spacing. These settings rebuild the ocean scene when the slider is released.
- The OBJ source file is not modified; scaling is applied to the in-memory scene geometry.

The interactive preview intentionally prioritizes (not very well) responsiveness over image detail. Use **Preview (Raster)** or **Raytrace Scene** for the higher-quality still image.
