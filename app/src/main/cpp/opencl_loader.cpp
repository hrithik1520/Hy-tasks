// Loads the phone's own OpenCL driver (libOpenCL.so, Adreno GPUs) at runtime instead of linking
// against it. Linking would make the whole app fail to start on phones without it; this way a
// missing or incomplete driver just means llama.cpp finds no GPU and runs on the CPU.
//
// Every OpenCL entry point ggml-opencl uses is defined here as a 3-instruction arm64 trampoline
// that jumps to the driver's function, so argument lists never have to be repeated. All of them
// are reached only after clGetPlatformIDs (ggml's first OpenCL call) succeeded, which happens only
// when every required function was found.
#define CL_TARGET_OPENCL_VERSION 300
#define CL_USE_DEPRECATED_OPENCL_1_2_APIS
#include <CL/cl.h>
#include <android/log.h>
#include <dlfcn.h>

#include <mutex>

#define HY_HIDDEN __attribute__((visibility("hidden")))

// Always present in an OpenCL 2.0+ driver; ggml-opencl can't run without any of them.
#define HY_CL_REQUIRED(X)                                                                                 \
    X(clGetPlatformInfo) X(clGetDeviceIDs) X(clGetDeviceInfo) X(clCreateContext) X(clCreateCommandQueue)   \
    X(clCreateBuffer) X(clCreateSubBuffer) X(clCreateImage) X(clReleaseMemObject)                          \
    X(clCreateProgramWithSource) X(clCreateProgramWithBinary) X(clBuildProgram) X(clGetProgramInfo)        \
    X(clGetProgramBuildInfo) X(clReleaseProgram) X(clCreateKernel) X(clSetKernelArg) X(clGetKernelInfo)    \
    X(clGetKernelWorkGroupInfo) X(clReleaseKernel) X(clEnqueueNDRangeKernel) X(clEnqueueReadBuffer)        \
    X(clEnqueueWriteBuffer) X(clEnqueueCopyBuffer) X(clEnqueueFillBuffer) X(clEnqueueMapBuffer)            \
    X(clEnqueueUnmapMemObject) X(clEnqueueMarkerWithWaitList) X(clEnqueueBarrierWithWaitList)              \
    X(clWaitForEvents) X(clReleaseEvent) X(clFlush) X(clFinish)

// Newer or profiling-only calls: replaced by a stub that reports an error when the driver lacks them.
#define HY_CL_OPTIONAL(X) X(clGetEventProfilingInfo) X(clGetKernelSubGroupInfo) X(clCreateBufferWithProperties)

#define HY_CL_TRAMPOLINE(name)                                                   \
    extern "C" HY_HIDDEN void *hy_clp_##name = nullptr;                          \
    __asm__(".text\n"                                                            \
            ".balign 4\n"                                                        \
            ".globl " #name "\n"                                                 \
            ".hidden " #name "\n"                                                \
            ".type " #name ", %function\n" #name ":\n"                           \
            "adrp x16, hy_clp_" #name "\n"                                       \
            "ldr x16, [x16, :lo12:hy_clp_" #name "]\n"                           \
            "br x16\n"                                                           \
            ".size " #name ", .-" #name "\n");

HY_CL_REQUIRED(HY_CL_TRAMPOLINE)
HY_CL_OPTIONAL(HY_CL_TRAMPOLINE)

namespace {

cl_int CL_API_CALL missing_profiling_info(cl_event, cl_profiling_info, size_t, void *, size_t *) {
    return CL_PROFILING_INFO_NOT_AVAILABLE;
}

cl_int CL_API_CALL missing_subgroup_info(cl_kernel, cl_device_id, cl_kernel_sub_group_info, size_t, const void *,
                                         size_t, void *, size_t *) {
    return CL_INVALID_OPERATION;
}

cl_mem CL_API_CALL missing_buffer_with_properties(cl_context, const cl_mem_properties *, cl_mem_flags, size_t, void *,
                                                  cl_int *err) {
    if (err) *err = CL_INVALID_OPERATION;
    return nullptr;
}

using GetPlatformIds = cl_int(CL_API_CALL *)(cl_uint, cl_platform_id *, cl_uint *);
GetPlatformIds g_get_platform_ids = nullptr;

void load() {
    void *lib = dlopen("libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib) lib = dlopen("/vendor/lib64/libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib) {
        __android_log_print(ANDROID_LOG_INFO, "HyLlm", "No OpenCL driver: running on the CPU");
        return;
    }
    bool complete = true;
#define HY_CL_LOAD_REQUIRED(name)                                                                     \
    if (!(hy_clp_##name = dlsym(lib, #name))) {                                                        \
        __android_log_print(ANDROID_LOG_WARN, "HyLlm", "OpenCL driver lacks %s: running on the CPU", #name); \
        complete = false;                                                                              \
    }
    HY_CL_REQUIRED(HY_CL_LOAD_REQUIRED)
#undef HY_CL_LOAD_REQUIRED
    hy_clp_clGetEventProfilingInfo = dlsym(lib, "clGetEventProfilingInfo");
    if (!hy_clp_clGetEventProfilingInfo) hy_clp_clGetEventProfilingInfo = (void *)&missing_profiling_info;
    hy_clp_clGetKernelSubGroupInfo = dlsym(lib, "clGetKernelSubGroupInfo");
    if (!hy_clp_clGetKernelSubGroupInfo) hy_clp_clGetKernelSubGroupInfo = (void *)&missing_subgroup_info;
    hy_clp_clCreateBufferWithProperties = dlsym(lib, "clCreateBufferWithProperties");
    if (!hy_clp_clCreateBufferWithProperties) hy_clp_clCreateBufferWithProperties = (void *)&missing_buffer_with_properties;
    auto get_ids = reinterpret_cast<GetPlatformIds>(dlsym(lib, "clGetPlatformIDs"));
    if (complete && get_ids) g_get_platform_ids = get_ids;
}

}  // namespace

extern "C" HY_HIDDEN CL_API_ENTRY cl_int CL_API_CALL clGetPlatformIDs(cl_uint num_entries, cl_platform_id *platforms,
                                                                        cl_uint *num_platforms) {
    static std::once_flag once;
    std::call_once(once, load);
    if (!g_get_platform_ids) {
        if (num_platforms) *num_platforms = 0;
        return CL_INVALID_PLATFORM;  // ggml then reports no OpenCL devices
    }
    return g_get_platform_ids(num_entries, platforms, num_platforms);
}
