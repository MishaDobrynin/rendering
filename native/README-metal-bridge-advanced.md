# Technical guide: `native/metal_bridge.m`

This document describes the implementation in `native/metal_bridge.m` on the `modern` branch. It is written for a programmer comfortable with C-style memory, arrays, functions, and Java, but who has limited Objective-C experience.

The file is a JNI bridge. It does not implement ray tracing itself. Its job is to expose a small native API to Java and translate Java-owned scene data into resources that the Metal GPU API can use.

---

## 1. Where this file sits in the rendering pipeline

The relevant call sequence is:

1. Java imports the model and builds a `Scene`.
2. Java constructs the acceleration structure and packs the scene into arrays in the layout expected by the Metal shader.
3. `MetalPathTracer` calls `nativeCreate(...)`.
4. This file allocates Metal buffers and a Metal texture array, copies the packed data into them, compiles the shader source, and creates a compute pipeline.
5. For each output band, Java calls `nativeRenderRows(...)`.
6. This file creates a command buffer, binds the resources, dispatches the compute kernel, waits for completion, and copies the resulting pixels into a Java `int[]`.
7. Java writes those pixels into the PNG output.

The bridge therefore has three main responsibilities:

- **Resource creation:** create and retain the GPU-side representation of a scene.
- **Command submission:** configure and execute a Metal compute dispatch.
- **Data transfer:** move data between Java arrays and Metal-visible memory.

It does not parse OBJ files, calculate the BVH, generate camera rays, shade surfaces, or encode PNG files.

## 2. Imports and language boundary

```objc
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
```

- `Foundation` supplies Objective-C runtime support and common classes such as `NSString`, `NSError`, and `NSAutoreleasePool` functionality used by `@autoreleasepool`.
- `Metal` declares the Objective-C interfaces for Metal devices, buffers, textures, command queues, encoders, and pipelines.
- `jni.h` defines the JNI types and function table used to communicate with the JVM.
- `stdint.h` provides fixed-width integer types such as `uint64_t` and `int32_t`.
- `stdlib.h` and `string.h` provide C library facilities. In the current file they are included for native utility operations and compatibility with C declarations.

Objective-C is a superset of C with an object system. This source uses ordinary C declarations and pointer operations alongside Objective-C message expressions such as `[device newCommandQueue]`.

An Objective-C message expression has the form:

```objc
[receiver messageName:argument]
```

It invokes a method on `receiver`. The method name can contain multiple argument labels. For example:

```objc
[device newBufferWithLength:length options:MTLResourceStorageModeShared]
```

The expression returns an object pointer conforming to the declared return type.

## 3. `WarshipMetalContext`: the lifetime container for a scene

```objc
@interface WarshipMetalContext : NSObject
@property(nonatomic, strong) id<MTLDevice> device;
...
@end
```

`@interface` declares an Objective-C class. `NSObject` is the standard root class for ordinary Objective-C objects.

A property declaration describes an object or scalar value associated with each instance. The compiler synthesizes accessor methods unless custom accessors are supplied.

### 3.1 Understanding `id<MTLDevice>`

`id` is an Objective-C object pointer whose concrete class is not specified at compile time. The protocol qualification `<MTLDevice>` states that the object is expected to implement the `MTLDevice` protocol.

This is not a C++ template and does not instantiate a generic type. It is a protocol-qualified Objective-C object reference.

Metal uses protocol-qualified references because the concrete implementation of a device, buffer, texture, or command queue is supplied by the system's Metal runtime.

### 3.2 Property ownership qualifiers

The properties use two different ownership declarations:

- `strong`: the context holds a strong reference to the object. Under Automatic Reference Counting (ARC), the object remains alive while at least one strong reference owns it.
- `assign`: the property stores a scalar value without Objective-C object ownership semantics. It is appropriate for the `NSUInteger` counts in this class.

The Metal objects are `strong` because the context must keep them alive between scene creation and later render calls. The counts are `assign` because they are ordinary integer values.

### 3.3 Why the context stores these particular objects

| Property | Role |
|---|---|
| `device` | The Metal device used to allocate resources and create commands |
| `queue` | The queue to which command buffers are submitted |
| `pipeline` | The compiled compute pipeline for `metalPathTraceKernel` |
| `triangleBuffer` | Packed triangle and material records |
| `nodeBoundsBuffer` | BVH node bounding boxes |
| `nodeMetadataBuffer` | BVH child indices and leaf ranges |
| `textureArray` | Diffuse images stored as layers of one 2D texture array |
| `textureSampler` | Filtering and UV address-mode configuration |
| `triangleCount` | Number of triangle records |
| `nodeCount` | Number of BVH nodes |
| `textureLayers` | Number of images in the texture array |

The class has an empty `@implementation` because its declared properties are sufficient. ARC manages the strong object references.

## 4. Static pipeline cache

```objc
static id<MTLComputePipelineState> gSharedPipeline = nil;
static NSString *gSharedShaderSource = nil;
static id<MTLDevice> gSharedDevice = nil;
```

These variables have file scope and static storage duration. They are shared by calls to the JNI functions in this source file.

- `gSharedPipeline` stores a compiled compute pipeline.
- `gSharedShaderSource` stores the source text used to build that pipeline.
- `gSharedDevice` retains the Metal device associated with the most recent pipeline creation.

The cache is checked inside `@synchronized([WarshipMetalContext class])`. `@synchronized` is an Objective-C language construct that acquires a lock associated with the supplied object for the duration of the block. It prevents two threads from compiling and replacing the shared pipeline simultaneously.

The current cache invalidation condition compares the shader source string. The file records `gSharedDevice`, but the condition does not compare it when deciding whether to reuse the pipeline. This is acceptable for the current design, which uses `MTLCreateSystemDefaultDevice()` for context creation, but would need revisiting if device selection were added.

The cache stores one shared pipeline, not a separate pipeline for every scene. Scene-specific geometry and textures remain in each `WarshipMetalContext`.

## 5. JNI: how the JVM calls the native functions

JNI is the Java Native Interface. It defines a binary calling convention through which Java code can invoke native functions and native code can access Java objects and arrays.

The native function names have this form:

```objc
Java_renderer_MetalPathTracer_nativeCreate
```

The components correspond to the Java package, class, and method:

- package: `renderer`
- class: `MetalPathTracer`
- method: `nativeCreate`

The Java declaration must be marked `native` and must have a compatible parameter and return-type sequence. The native symbol name and signature are therefore part of an interface contract.

### 5.1 `JNIEnv *env`

Each JNI function receives a `JNIEnv *`. It provides access to the JNI function table for the current thread. The code calls functions through that table:

```objc
(*env)->GetArrayLength(env, trianglesArray);
```

This syntax looks unusual because JNI is a C interface. `env` points to a structure containing function pointers. The first argument to each function is the environment pointer itself.

Do not retain `JNIEnv *` and use it on another thread. It is valid for the current JNI call/thread context.

### 5.2 JNI reference types

Parameters such as `jfloatArray`, `jintArray`, `jbyteArray`, and `jstring` are opaque JNI references. They are not raw C pointers to Java's internal array or string representation.

The bridge must use JNI functions to inspect or copy their contents. It must not dereference them as if they were C arrays.

### 5.3 Throwing Java exceptions

```objc
static void throwJava(JNIEnv *env, const char *className, NSString *message)
```

This helper:

1. Finds a Java class by its slash-separated name, for example `java/lang/IllegalStateException`.
2. Converts the Objective-C message to UTF-8.
3. Calls `ThrowNew` to set a Java exception as pending on the current thread.

A native method does not need to return a Java exception object. JNI marks an exception as pending, and Java observes it when control returns from the native call.

The helper uses `FindClass`, which is suitable for the exception classes used here. More complex class loading scenarios can require care because class lookup depends on the calling class loader.

## 6. `makeSharedBuffer`: allocating CPU/GPU-visible memory

```objc
static id<MTLBuffer> makeSharedBuffer(id<MTLDevice> device, NSUInteger length)
```

The helper creates a Metal buffer of `length` bytes using:

```objc
[device newBufferWithLength:length
                    options:MTLResourceStorageModeShared]
```

A Metal buffer is a linear allocation that can be bound to a shader. `MTLResourceStorageModeShared` requests storage accessible to both CPU and GPU. This is particularly useful on Apple Silicon, where CPU and GPU share unified physical memory.

Shared storage does not mean that every access is free. CPU/GPU synchronization and cache-coherency rules still matter. In this implementation, the bridge fills the buffers before submitting GPU work, and the GPU reads them during the dispatch.

The helper changes a zero-byte request to 16 bytes. Metal resources cannot usefully be created with a zero length, and the minimum allocation avoids that case.

## 7. `nativeDeviceName`: testing Metal device availability

```objc
JNIEXPORT jstring JNICALL
Java_renderer_MetalPathTracer_nativeDeviceName(...)
```

`JNIEXPORT` and `JNICALL` are JNI macros controlling symbol visibility and calling convention.

The function creates an autorelease pool, calls `MTLCreateSystemDefaultDevice()`, and returns the device's name as a Java string. If Metal returns `nil`, the function returns `NULL`, which Java receives as `null`.

The function does not create a rendering context or allocate scene resources. It is a capability query.

## 8. `nativeCreate`: constructing a GPU scene context

This is the largest setup function. Its arguments are:

```text
jfloatArray trianglesArray
jfloatArray nodeBoundsArray
jintArray   nodeMetadataArray
jbyteArray  textureBytesArray
jint        textureSize
jint        textureLayers
jstring     shaderSourceString
```

The arrays are already packed by Java. The bridge validates their dimensions, allocates Metal resources, copies the data, creates the texture array and sampler, compiles the shader, and returns an opaque context handle.

### 8.1 `@autoreleasepool`

```objc
@autoreleasepool {
    ...
}
```

An autorelease pool collects temporary Objective-C objects that have been autoreleased. When the block exits, those temporary references are released.

ARC manages strong references, but autorelease pools still control the lifetime of autoreleased temporary objects. JNI entry points use a pool so temporary Foundation and Metal wrapper objects do not accumulate indefinitely across repeated native calls.

### 8.2 Input validation and array lengths

The bridge first rejects null JNI references. It then obtains the Java array lengths:

```objc
jsize triangleFloats = (*env)->GetArrayLength(env, trianglesArray);
```

`jsize` is JNI's signed integer type for array lengths and indices. The required divisibility checks encode the record layouts:

- 48 floats per triangle.
- 8 floats per BVH bounds record.
- 4 integers per BVH metadata record.

The bounds and metadata arrays must describe the same number of nodes.

The texture byte count is checked against:

```text
textureSize × textureSize × 4 × textureLayers
```

The calculation uses `uint64_t` so that intermediate multiplication does not overflow a 32-bit integer before comparison.

### 8.3 Creating the context and command queue

```objc
WarshipMetalContext *context = [WarshipMetalContext new];
context.device = device;
context.queue = [device newCommandQueue];
```

`[WarshipMetalContext new]` allocates and initializes an instance. Assigning the device and queue to strong properties makes the context retain them.

A command queue is the object to which command buffers are submitted. It establishes an ordered stream of GPU work. It does not itself execute the path-tracing kernel.

### 8.4 Allocating and populating the geometry buffers

The bridge allocates three `MTLBuffer` objects:

- triangle data: `triangleFloats * sizeof(float)` bytes;
- node bounds: `boundsFloats * sizeof(float)` bytes;
- node metadata: `metadataInts * sizeof(int32_t)` bytes.

It then uses `GetFloatArrayRegion` and `GetIntArrayRegion` to copy Java array contents into the memory returned by each Metal buffer's `contents` method.

`GetFloatArrayRegion` copies values into a native destination. It does not expose a long-lived pointer to the JVM's internal array storage. This is useful here because the destination is the Metal buffer's CPU-visible memory.

After each group of JNI operations, `ExceptionCheck` detects whether JNI has raised an exception, for example due to an invalid array operation.

### 8.5 Creating the texture array

The bridge constructs an `MTLTextureDescriptor` and sets:

- `textureType = MTLTextureType2DArray`;
- `pixelFormat = MTLPixelFormatRGBA8Unorm_sRGB`;
- width and height to `textureSize`;
- `arrayLength` to `textureLayers`;
- one mip level;
- shared storage;
- shader-read usage.

A 2D texture array is one texture resource containing multiple same-sized 2D image slices. The shader samples a slice using a layer index.

The format is four 8-bit channels. The `sRGB` format tells Metal that RGB texel values are stored in sRGB encoding and should be interpreted accordingly during sampling; alpha is not sRGB encoded.

The byte array is accessed using:

```objc
jbyte *textureData =
    (*env)->GetByteArrayElements(env, textureBytesArray, NULL);
```

Unlike `GetByteArrayRegion`, `GetByteArrayElements` returns a pointer that can be used to read the array. The JVM may pin the underlying array or return a temporary copy. The caller must always pair a successful acquisition with `ReleaseByteArrayElements`.

For each layer, the bridge computes the byte offset:

```text
layer × bytesPerImage
```

and copies the image into the matching texture slice using `replaceRegion`.

`JNI_ABORT` tells the JVM that native code did not modify the Java array and that changes do not need to be copied back. It does not mean “do not release”; it still releases the acquired array access.

### 8.6 Sampler configuration

The `MTLSamplerDescriptor` specifies how the shader reads the texture:

- linear minification filtering;
- linear magnification filtering;
- no mip filtering;
- repeat addressing for S/U and T/V.

Linear filtering interpolates neighboring texels. Repeat addressing wraps UV coordinates outside the `[0,1]` interval.

The sampler is a separate object from the texture. The texture stores image data; the sampler defines how coordinates are converted into filtered samples.

### 8.7 Converting the shader string

`GetStringUTFChars` obtains a UTF-8 representation of the Java string. The bridge creates an `NSString` from that data and then calls `ReleaseStringUTFChars`.

The release is required because the JVM may have pinned or copied the string's character storage. The `NSString` is an Objective-C object with its own lifetime.

### 8.8 Compiling MSL and creating a pipeline

The shader is compiled at runtime:

```objc
[device newLibraryWithSource:shaderSource options:nil error:&compileError]
```

The result is an `MTLLibrary`, which contains compiled Metal functions. The bridge looks up the function named `metalPathTraceKernel`, then creates an `MTLComputePipelineState`.

A compute pipeline describes the compiled compute function and the device-specific state required to dispatch it. It is not a command buffer and does not represent one render.

The bridge stores the pipeline in the shared cache and assigns it to the context. Compilation errors are translated into Java exceptions with the Metal compiler's error description.

Finally:

```objc
return (jlong)(intptr_t)CFBridgingRetain(context);
```

`CFBridgingRetain` transfers the Objective-C object into a retained Core Foundation-style opaque pointer. The pointer is converted through `intptr_t` and then to `jlong`, so it can cross the JNI boundary as a Java `long`.

The handle is opaque to Java. Java must not inspect or modify its numeric value.

## 9. `nativeRenderRows`: submitting one compute dispatch

The render method receives:

- a context handle;
- full image width and height;
- samples per pixel;
- first row and row count for the requested band;
- a Java array containing camera and lighting parameters.

It converts the opaque handle back to an Objective-C pointer:

```objc
WarshipMetalContext *context =
    (__bridge WarshipMetalContext *)(void *)(intptr_t)handle;
```

`__bridge` performs a cast between a Core Foundation pointer and an Objective-C pointer without transferring ownership. The retained ownership created in `nativeCreate` remains in place until `nativeRelease`.

### 9.1 Band validation and output allocation

The bridge verifies positive dimensions and sample count, and checks that the requested row interval lies within the image.

The number of pixels is calculated as a 64-bit value:

```text
pixelCount = width × rows
```

It is checked against the maximum Java array length and native allocation size before being converted to `NSUInteger`.

The output buffer stores one 32-bit integer per pixel. Its size is therefore:

```text
outputBytes = pixelCount × sizeof(uint32_t)
```

### 9.2 Building the uniform buffer

The shader expects 33 floats. The bridge initializes a local C array:

```objc
float params[33] = {0};
```

The bridge writes render dimensions and scene counts into indices 0–7, then copies the 25 Java camera/light values into indices 8–32.

The array is uploaded into a new shared `MTLBuffer` using `newBufferWithBytes`. The MSL kernel reads this memory through its `constant float *p` argument.

The order is an ABI contract. The compiler cannot detect if Java puts the sun direction in the wrong three indices; it will simply interpret those floats according to the shader's indexing.

### 9.3 Command buffer and compute encoder

```objc
id<MTLCommandBuffer> commandBuffer = [context.queue commandBuffer];
id<MTLComputeCommandEncoder> encoder = [commandBuffer computeCommandEncoder];
```

A command buffer records a set of GPU commands for later submission. A compute command encoder records compute-specific commands into that command buffer.

The bridge then:

1. Selects the compute pipeline.
2. Binds the triangle, BVH, output, and uniform buffers.
3. Binds the diffuse texture array.
4. Binds the texture sampler.
5. Configures the dispatch dimensions.
6. Ends encoding.
7. Commits the command buffer.

The binding indices must match the `[[buffer(n)]]`, `[[texture(n)]]`, and `[[sampler(n)]]` attributes in the MSL kernel.

### 9.4 Threadgroup dimensions

The bridge requests 8 × 8 threads per threadgroup. It rounds the number of groups upward:

```text
groupsX = ceil(width / 8)
groupsY = ceil(rows / 8)
```

The dispatched grid can therefore be slightly larger than the exact image band. The shader checks `gid.x` and `gid.y` and exits for out-of-range threads.

### 9.5 Waiting for GPU completion

```objc
[commandBuffer commit];
[commandBuffer waitUntilCompleted];
```

`commit` submits the encoded commands to the GPU queue. `waitUntilCompleted` blocks the calling CPU thread until the command buffer finishes.

This makes `nativeRenderRows` synchronous from Java's perspective. Java cannot begin writing the returned band until the GPU has completed it. The bridge checks the command buffer's status and translates GPU execution failures into Java exceptions.

### 9.6 Returning the band

The bridge creates a Java `int[]` with `NewIntArray`, then copies the contents of the output buffer into it using `SetIntArrayRegion`.

The shader writes each pixel in `0xAARRGGBB`-style packed integer form. The Java side receives those values and writes the corresponding image rows.

## 10. `nativeRelease`: releasing the scene context

```objc
id object = CFBridgingRelease((void *)(intptr_t)handle);
```

This reverses the ownership transfer performed by `CFBridgingRetain`.

`CFBridgingRelease` transfers the retained reference back into ARC-managed Objective-C ownership. The local variable keeps the object alive until the scope ends; ARC can then release it. Releasing the context releases its strong references to the buffers, textures, sampler, queue, pipeline, and device, subject to any other owners.

A handle must be released exactly once. Releasing the same retained handle more than once is invalid.

## 11. Objective-C concepts used in this file

| Syntax | Meaning in this source |
|---|---|
| `@interface` | Declares an Objective-C class and its properties |
| `@implementation` | Provides the class implementation |
| `@property` | Declares an object or scalar property with accessors |
| `strong` | Retains an Objective-C object under ARC |
| `assign` | Stores a scalar value without object ownership |
| `id<Protocol>` | Object pointer expected to implement a protocol |
| `[object message]` | Sends an Objective-C message |
| `nil` | Null Objective-C object reference |
| `@autoreleasepool` | Bounds the lifetime of autoreleased temporary objects |
| `@synchronized` | Locks a monitor for the duration of a block |
| `@` before a string | Creates an Objective-C string literal (`NSString`) |
| `__bridge` | Casts between Objective-C and Core Foundation pointer types without changing ownership |
| `CFBridgingRetain` | Creates a retained opaque reference |
| `CFBridgingRelease` | Transfers a retained opaque reference back to ARC |

## 12. Important implementation constraints

1. **The packed data layout is shared across languages.** Java's triangle and BVH packers must agree with the MSL structures and indexing.
2. **The texture array requires uniform layer dimensions.** Java must prepare every image in the same RGBA dimensions expected by this bridge.
3. **The shader resource and entry-point names are fixed.** Java loads `/renderer/PathTrace.metal`; the bridge looks for `metalPathTraceKernel`.
4. **The native calls are synchronous.** Each band waits for the GPU and copies its result to a Java array before returning.
5. **The pipeline cache is global to this native library.** If future code supports multiple Metal devices, cache validity should include the device as well as shader source.
6. **Metal memory is not the Java heap.** Increasing `-Xmx` does not increase the amount of memory Metal can allocate.
7. **The handle is an ownership-bearing pointer.** It must be treated as opaque by Java and released once through `nativeRelease`.
8. **This file is macOS-specific.** It depends on Apple's Foundation and Metal frameworks and cannot be built as a normal cross-platform JNI library.
