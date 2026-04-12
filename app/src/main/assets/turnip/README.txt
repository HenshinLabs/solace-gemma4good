Turnip Vulkan Driver Asset Hook
===============================

This app includes runtime hooks for Turnip-based Vulkan acceleration used by
GGUF and Diffusers backends.

Expected asset files:
- turnip/icd.d/freedreno_icd.aarch64.json
- turnip/libvulkan_freedreno.so

At runtime, engines extract these assets into app-private storage and set
VK_ICD_FILENAMES to the extracted ICD JSON path when GPU offload is enabled.

Notes:
- If libvulkan_freedreno.so is absent, the app will automatically fall back to
  CPU/native default Vulkan drivers.
- Keep the ICD JSON and shared library versions matched.
