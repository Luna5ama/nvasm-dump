# nvmasm dump

A tool to dump NVIDIA Vendor-Specific OpenGL Assembly

## Hardware Requirements

- A GPU with proper OpenGL 4.6 support
- Java 8 or higher

## Usage

`java -jar nvasm-dump.jar <output directory> <input shader files>...`

- `<output directory>`: Directory where the dumped assembly files will be saved
- `<input shader files>`: One or more shader files to be processed
    - Glob patterns are supported
    - Shader files must be preprocessed
    - Shader files must have extension that indicates shader type
        - Supported shader file extensions:
            - Vertex Shader: `.vert`, `.vsh`, `.vert.glsl`
            - Fragment Shader: `.frag`, `.fsh`, `.frag.glsl`
            - Geometry Shader: `.geom`, `.gsh`, `.geom.glsl`
            - Compute Shader: `.comp`, `.csh`, `.comp.glsl`
            - Tessellation Control Shader: `.tesc`, `.tcs`, `.tesc.glsl`
            - Tessellation Evaluation Shader: `.tese`, `.tes`, `.tese.glsl`
            - Task Shader: `.task`, `.task.glsl`
            - Mesh Shader: `.mesh`, `.mesh.glsl`

### Examples of using the tool with Iris shaders

- Dumping for all shaders\
  `java -jar nvasm-dump.jar ./nvasm .../.minecraft/patched_shaders/*`
- Dumping all composite shaders\
  `java -jar nvasm-dump.jar ./nvasm .../.minecraft/patched_shaders/*composite*` (Preceding `*` is needed because
  Iris appends indices to shader files)
- Dumping only deferred6 and deferred9\
  `java -jar nvasm-dump.jar ./nvasm .../.minecraft/patched_shaders/*deferred6* .../.minecraft/patched_shaders/*deferred9*`

## Build Instructions

1. Clone this repo
2. Build this project using Gradle