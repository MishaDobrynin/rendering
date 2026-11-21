#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

@interface WarshipMetalContext : NSObject
@property(nonatomic, strong) id<MTLDevice> device;
@property(nonatomic, strong) id<MTLCommandQueue> queue;
@property(nonatomic, strong) id<MTLComputePipelineState> pipeline;
@property(nonatomic, strong) id<MTLBuffer> triangleBuffer;
@property(nonatomic, strong) id<MTLBuffer> nodeBoundsBuffer;
@property(nonatomic, strong) id<MTLBuffer> nodeMetadataBuffer;
@property(nonatomic, strong) id<MTLTexture> textureArray;
@property(nonatomic, strong) id<MTLSamplerState> textureSampler;
@property(nonatomic, assign) NSUInteger triangleCount;
@property(nonatomic, assign) NSUInteger nodeCount;
@property(nonatomic, assign) NSUInteger textureLayers;
@end
@implementation WarshipMetalContext
@end

static id<MTLComputePipelineState> gSharedPipeline = nil;
static NSString *gSharedShaderSource = nil;
static id<MTLDevice> gSharedDevice = nil;

static void throwJava(JNIEnv *env, const char *className, NSString *message) {
    jclass cls = (*env)->FindClass(env, className);
    if (cls != NULL) {
        const char *utf8 = message == nil ? "Unknown Metal error" : [message UTF8String];
        (*env)->ThrowNew(env, cls, utf8 != NULL ? utf8 : "Unknown Metal error");
    }
}

static id<MTLBuffer> makeSharedBuffer(id<MTLDevice> device, NSUInteger length) {
    if (length == 0) length = 16;
    return [device newBufferWithLength:length options:MTLResourceStorageModeShared];
}

JNIEXPORT jstring JNICALL Java_renderer_MetalPathTracer_nativeDeviceName(JNIEnv *env, jclass clazz) {
    @autoreleasepool {
        id<MTLDevice> device = MTLCreateSystemDefaultDevice();
        if (device == nil) return NULL;
        return (*env)->NewStringUTF(env, [[device name] UTF8String]);
    }
}

JNIEXPORT jlong JNICALL Java_renderer_MetalPathTracer_nativeCreate(
        JNIEnv *env, jclass clazz,
        jfloatArray trianglesArray,
        jfloatArray nodeBoundsArray,
        jintArray nodeMetadataArray,
        jbyteArray textureBytesArray,
        jint textureSize,
        jint textureLayers,
        jstring shaderSourceString) {
    @autoreleasepool {
        if (trianglesArray == NULL || nodeBoundsArray == NULL || nodeMetadataArray == NULL ||
            textureBytesArray == NULL || shaderSourceString == NULL) {
            throwJava(env, "java/lang/IllegalArgumentException", @"Metal scene data or shader source is missing.");
            return 0;
        }
        id<MTLDevice> device = MTLCreateSystemDefaultDevice();
        if (device == nil) {
            throwJava(env, "java/lang/IllegalStateException", @"No Metal-capable GPU was found on this Mac.");
            return 0;
        }

        jsize triangleFloats = (*env)->GetArrayLength(env, trianglesArray);
        jsize boundsFloats = (*env)->GetArrayLength(env, nodeBoundsArray);
        jsize metadataInts = (*env)->GetArrayLength(env, nodeMetadataArray);
        jsize textureByteCount = (*env)->GetArrayLength(env, textureBytesArray);
        if (triangleFloats == 0 || triangleFloats % 48 != 0 || boundsFloats == 0 || boundsFloats % 8 != 0 ||
            metadataInts == 0 || metadataInts % 4 != 0 || boundsFloats != (metadataInts / 4) * 8 || textureSize < 1 || textureLayers < 1) {
            throwJava(env, "java/lang/IllegalArgumentException", @"Packed Metal geometry or texture dimensions are invalid.");
            return 0;
        }
        uint64_t requiredTextureBytes = (uint64_t)textureSize * (uint64_t)textureSize * 4ull * (uint64_t)textureLayers;
        if (requiredTextureBytes != (uint64_t)textureByteCount) {
            throwJava(env, "java/lang/IllegalArgumentException", @"The packed texture array length does not match its dimensions.");
            return 0;
        }

        WarshipMetalContext *context = [WarshipMetalContext new];
        context.device = device;
        context.queue = [device newCommandQueue];
        context.triangleCount = (NSUInteger)(triangleFloats / 48);
        context.nodeCount = (NSUInteger)(metadataInts / 4);
        context.textureLayers = (NSUInteger)textureLayers;
        if (context.queue == nil) {
            throwJava(env, "java/lang/IllegalStateException", @"Metal could not create a command queue.");
            return 0;
        }

        context.triangleBuffer = makeSharedBuffer(device, (NSUInteger)triangleFloats * sizeof(float));
        context.nodeBoundsBuffer = makeSharedBuffer(device, (NSUInteger)boundsFloats * sizeof(float));
        context.nodeMetadataBuffer = makeSharedBuffer(device, (NSUInteger)metadataInts * sizeof(int32_t));
        if (context.triangleBuffer == nil || context.nodeBoundsBuffer == nil || context.nodeMetadataBuffer == nil) {
            throwJava(env, "java/lang/OutOfMemoryError", @"Metal could not allocate shared GPU buffers for the imported scene. Try a smaller model or close other GPU-intensive applications.");
            return 0;
        }
        (*env)->GetFloatArrayRegion(env, trianglesArray, 0, triangleFloats, (jfloat *)[context.triangleBuffer contents]);
        (*env)->GetFloatArrayRegion(env, nodeBoundsArray, 0, boundsFloats, (jfloat *)[context.nodeBoundsBuffer contents]);
        (*env)->GetIntArrayRegion(env, nodeMetadataArray, 0, metadataInts, (jint *)[context.nodeMetadataBuffer contents]);
        if ((*env)->ExceptionCheck(env)) return 0;

        MTLTextureDescriptor *textureDescriptor = [[MTLTextureDescriptor alloc] init];
        textureDescriptor.textureType = MTLTextureType2DArray;
        textureDescriptor.pixelFormat = MTLPixelFormatRGBA8Unorm_sRGB;
        textureDescriptor.width = (NSUInteger)textureSize;
        textureDescriptor.height = (NSUInteger)textureSize;
        textureDescriptor.depth = 1;
        textureDescriptor.arrayLength = (NSUInteger)textureLayers;
        textureDescriptor.mipmapLevelCount = 1;
        textureDescriptor.sampleCount = 1;
        textureDescriptor.storageMode = MTLStorageModeShared;
        textureDescriptor.usage = MTLTextureUsageShaderRead;
        context.textureArray = [device newTextureWithDescriptor:textureDescriptor];
        if (context.textureArray == nil) {
            throwJava(env, "java/lang/OutOfMemoryError", @"Metal could not allocate the diffuse texture array.");
            return 0;
        }
        jbyte *textureData = (*env)->GetByteArrayElements(env, textureBytesArray, NULL);
        if (textureData == NULL) return 0;
        MTLRegion region = MTLRegionMake2D(0, 0, (NSUInteger)textureSize, (NSUInteger)textureSize);
        NSUInteger bytesPerRow = (NSUInteger)textureSize * 4;
        NSUInteger bytesPerImage = bytesPerRow * (NSUInteger)textureSize;
        for (NSUInteger layer = 0; layer < (NSUInteger)textureLayers; ++layer) {
            const uint8_t *slice = (const uint8_t *)textureData + layer * bytesPerImage;
            [context.textureArray replaceRegion:region mipmapLevel:0 slice:layer
                                       withBytes:slice bytesPerRow:bytesPerRow bytesPerImage:bytesPerImage];
        }
        (*env)->ReleaseByteArrayElements(env, textureBytesArray, textureData, JNI_ABORT);

        MTLSamplerDescriptor *samplerDescriptor = [[MTLSamplerDescriptor alloc] init];
        samplerDescriptor.minFilter = MTLSamplerMinMagFilterLinear;
        samplerDescriptor.magFilter = MTLSamplerMinMagFilterLinear;
        samplerDescriptor.mipFilter = MTLSamplerMipFilterNotMipmapped;
        samplerDescriptor.sAddressMode = MTLSamplerAddressModeRepeat;
        samplerDescriptor.tAddressMode = MTLSamplerAddressModeRepeat;
        context.textureSampler = [device newSamplerStateWithDescriptor:samplerDescriptor];

        const char *shaderUtf8 = (*env)->GetStringUTFChars(env, shaderSourceString, NULL);
        if (shaderUtf8 == NULL) return 0;
        NSString *shaderSource = [[NSString alloc] initWithUTF8String:shaderUtf8];
        (*env)->ReleaseStringUTFChars(env, shaderSourceString, shaderUtf8);
        @synchronized([WarshipMetalContext class]) {
            if (gSharedPipeline == nil || gSharedShaderSource == nil || ![gSharedShaderSource isEqualToString:shaderSource]) {
                NSError *compileError = nil;
                id<MTLLibrary> library = [device newLibraryWithSource:shaderSource options:nil error:&compileError];
                if (library == nil) {
                    NSString *detail = [NSString stringWithFormat:@"Metal shader compilation failed: %@", compileError.localizedDescription ?: @"unknown compiler error"];
                    throwJava(env, "java/lang/IllegalStateException", detail);
                    return 0;
                }
                id<MTLFunction> function = [library newFunctionWithName:@"metalPathTraceKernel"];
                if (function == nil) {
                    throwJava(env, "java/lang/IllegalStateException", @"The Metal shader does not define metalPathTraceKernel.");
                    return 0;
                }
                NSError *pipelineError = nil;
                id<MTLComputePipelineState> pipeline = [device newComputePipelineStateWithFunction:function error:&pipelineError];
                if (pipeline == nil) {
                    NSString *detail = [NSString stringWithFormat:@"Could not create the Metal compute pipeline: %@", pipelineError.localizedDescription ?: @"unknown pipeline error"];
                    throwJava(env, "java/lang/IllegalStateException", detail);
                    return 0;
                }
                gSharedDevice = device;
                gSharedShaderSource = [shaderSource copy];
                gSharedPipeline = pipeline;
            }
            context.pipeline = gSharedPipeline;
        }
        return (jlong)(intptr_t)CFBridgingRetain(context);
    }
}

JNIEXPORT jintArray JNICALL Java_renderer_MetalPathTracer_nativeRenderRows(
        JNIEnv *env, jclass clazz, jlong handle,
        jint width, jint height, jint samplesPerPixel, jint firstY, jint rows,
        jfloatArray cameraAndLightArray) {
    @autoreleasepool {
        WarshipMetalContext *context = (__bridge WarshipMetalContext *)(void *)(intptr_t)handle;
        if (context == nil || width < 1 || height < 1 || samplesPerPixel < 1 || firstY < 0 || rows < 1 || firstY + rows > height) {
            throwJava(env, "java/lang/IllegalArgumentException", @"Invalid Metal row-band render request.");
            return NULL;
        }
        if (cameraAndLightArray == NULL || (*env)->GetArrayLength(env, cameraAndLightArray) < 25) {
            throwJava(env, "java/lang/IllegalArgumentException", @"Metal camera and lighting uniforms are incomplete.");
            return NULL;
        }
        uint64_t pixelCount = (uint64_t)width * (uint64_t)rows;
        if (pixelCount > (uint64_t)INT32_MAX || pixelCount * sizeof(uint32_t) > (uint64_t)NSUIntegerMax) {
            throwJava(env, "java/lang/OutOfMemoryError", @"The requested Metal output band is too large for a Java array.");
            return NULL;
        }
        NSUInteger outputBytes = (NSUInteger)(pixelCount * sizeof(uint32_t));
        id<MTLBuffer> outputBuffer = makeSharedBuffer(context.device, outputBytes);
        if (outputBuffer == nil) {
            throwJava(env, "java/lang/OutOfMemoryError", @"Metal could not allocate the output row band.");
            return NULL;
        }

        float params[33] = {0};
        params[0] = (float)width;
        params[1] = (float)height;
        params[2] = (float)samplesPerPixel;
        params[3] = (float)firstY;
        params[4] = (float)rows;
        params[5] = (float)context.triangleCount;
        params[6] = (float)context.nodeCount;
        params[7] = (float)context.textureLayers;
        (*env)->GetFloatArrayRegion(env, cameraAndLightArray, 0, 25, params + 8);
        if ((*env)->ExceptionCheck(env)) return NULL;
        id<MTLBuffer> uniformBuffer = [context.device newBufferWithBytes:params length:sizeof(params) options:MTLResourceStorageModeShared];
        if (uniformBuffer == nil) {
            throwJava(env, "java/lang/OutOfMemoryError", @"Metal could not allocate the render uniforms.");
            return NULL;
        }

        id<MTLCommandBuffer> commandBuffer = [context.queue commandBuffer];
        id<MTLComputeCommandEncoder> encoder = [commandBuffer computeCommandEncoder];
        if (commandBuffer == nil || encoder == nil) {
            throwJava(env, "java/lang/IllegalStateException", @"Metal could not create a compute command encoder.");
            return NULL;
        }
        [encoder setComputePipelineState:context.pipeline];
        [encoder setBuffer:context.triangleBuffer offset:0 atIndex:0];
        [encoder setBuffer:context.nodeBoundsBuffer offset:0 atIndex:1];
        [encoder setBuffer:context.nodeMetadataBuffer offset:0 atIndex:2];
        [encoder setBuffer:outputBuffer offset:0 atIndex:3];
        [encoder setBuffer:uniformBuffer offset:0 atIndex:4];
        [encoder setTexture:context.textureArray atIndex:0];
        [encoder setSamplerState:context.textureSampler atIndex:0];
        MTLSize threadsPerGroup = MTLSizeMake(8, 8, 1);
        MTLSize threadgroups = MTLSizeMake(((NSUInteger)width + 7) / 8, ((NSUInteger)rows + 7) / 8, 1);
        [encoder dispatchThreadgroups:threadgroups threadsPerThreadgroup:threadsPerGroup];
        [encoder endEncoding];
        [commandBuffer commit];
        [commandBuffer waitUntilCompleted];
        if (commandBuffer.status == MTLCommandBufferStatusError) {
            NSString *detail = [NSString stringWithFormat:@"Metal compute dispatch failed: %@", commandBuffer.error.localizedDescription ?: @"unknown GPU error"];
            throwJava(env, "java/lang/IllegalStateException", detail);
            return NULL;
        }

        jintArray result = (*env)->NewIntArray(env, (jsize)pixelCount);
        if (result == NULL) return NULL;
        (*env)->SetIntArrayRegion(env, result, 0, (jsize)pixelCount, (const jint *)[outputBuffer contents]);
        return result;
    }
}

JNIEXPORT void JNICALL Java_renderer_MetalPathTracer_nativeRelease(JNIEnv *env, jclass clazz, jlong handle) {
    @autoreleasepool {
        if (handle != 0) {
            id object = CFBridgingRelease((void *)(intptr_t)handle);
            (void)object;
        }
    }
}
