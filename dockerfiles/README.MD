# Docker Containers for AI-Serving

There are several dockerfiles to configure ONNX Runtime against different platform/runtime, but it's not a complete list, please refer to [Docker Containers for ONNX Runtime](https://github.com/microsoft/onnxruntime/tree/master/dockerfiles) to create Dockerfile for your own environment.

**Dockerfiles**
- No ONNX Runtime: [Dockerfile](Dockerfile.noonnx), [Instructions](#no-onnx-runtime)
- CPU: [Dockerfile](Dockerfile.source), [Instructions](#cpu)
- CUDA + CUDNN: [Dockerfile](Dockerfile.cuda), [Instructions](#cuda)
- nGraph: [Dockerfile](Dockerfile.ngraph), [Instructions](#ngraph)
- TensorRT: [Dockerfile](Dockerfile.tensorrt), [Instructions](#tensorrt)

**Published Docker Hub Images**

Use `docker pull` with any of the images and tags below to pull an image.

| Build Flavor      | Base Image              | Docker Image tags    | Latest         |
|-------------------|------------------------ |----------------------|----------------|
| No ONNX Runtime   | autodeployai/ai-serving | :0.1.0-noonnx        | :latest-noonnx |
| Source (CPU)      | autodeployai/ai-serving | :0.1.0               | :latest        |

## Building and using Docker images
**Use JAVA_OPTS environment**

All docker images can use `JAVA_OPTS` to specify extra JVM options when starting JVM in a container, for example:
```
docker run -e JAVA_OPTS="-Xms1g -Xmx2g" ...
```
The JVM will be started with `1g` amount of memory and will be able to use a maximum of `2g` amount of memory.


### No ONNX Runtime
**Ubuntu 16.04, without ONNX models support**

1. Build the docker image from the Dockerfile in this repository.
```
docker build -t ai-serving-noonnx -f Dockerfile.noonnx .
```

2. Run the Docker image
```
docker run -it -v {local_writable_directory}:/opt/ai-serving -p {local_http_port}:9090 -p {local_grpc_port}:9091 ai-serving-noonnx
```

### CPU
**Ubuntu 16.04, CPU, Java Bindings**

1. Build the docker image from the Dockerfile in this repository.
```
docker build -t ai-serving-source -f Dockerfile.source .
```

2. Run the Docker image
```
docker run -it -v {local_writable_directory}:/opt/ai-serving -p {local_http_port}:9090 -p {local_grpc_port}:9091 ai-serving-source
```

## CUDA
**Ubuntu 16.04, CUDA 10.0, CuDNN 7**

1. Build the docker image from the Dockerfile in this repository.
```
docker build -t ai-serving-cuda -f Dockerfile.cuda .
```

2. Run the Docker image
```
docker run -it -v {local_writable_directory}:/opt/ai-serving -p {local_http_port}:9090 -p {local_grpc_port}:9091 ai-serving-cuda
```

## nGraph
*Public Preview*

**Ubuntu 16.04, Python Bindings**

1. Build the docker image from the Dockerfile in this repository.
```
docker build -t ai-serving-ngraph -f Dockerfile.ngraph .
```

2. Run the Docker image
```
docker run -it -v {local_writable_directory}:/opt/ai-serving -p {local_http_port}:9090 -p {local_grpc_port}:9091 ai-serving-ngraph
```

## TensorRT
**Ubuntu 18.04, CUDA 10.1.243, TensorRT 6.0.1**

1. Build the docker image from the Dockerfile in this repository.
```
docker build -t ai-serving-trt -f Dockerfile.tensorrt .
```

2. Run the Docker image
```
docker run -it -v {local_writable_directory}:/opt/ai-serving -p {local_http_port}:9090 -p {local_grpc_port}:9091 ai-serving-trt
```

