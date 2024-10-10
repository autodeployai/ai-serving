# Docker Containers for AI-Serving

There are several dockerfiles to configure ONNX Runtime against different platform/runtime, but it's not a complete list, please refer to [Docker Containers for ONNX Runtime](https://github.com/microsoft/onnxruntime/tree/master/dockerfiles) to create Dockerfile for your own environment.

**Dockerfiles**
- CPU: [Dockerfile](Dockerfile), [Instructions](#cpu)
- CUDA + CUDNN: [Dockerfile](Dockerfile.cuda), [Instructions](#cuda)

**Published Docker Hub Images**

Use `docker pull` with any of the images and tags below to pull an image.

| Build Flavor      | Base Image              | Docker Hub                                                         | Latest        |
|-------------------|------------------------ |--------------------------------------------------------------------|---------------|
| Default CPU       | autodeployai/ai-serving | [Tags](https://hub.docker.com/r/autodeployai/ai-serving/tags)                                                     | :latest       |
| CUDA (GPU)        | autodeployai/ai-serving-cuda | [Tags](https://hub.docker.com/r/autodeployai/ai-serving-cuda/tags) | :latest   |

## Building and using Docker images
**Use JAVA_OPTS environment**

All docker images can use `JAVA_OPTS` to specify extra JVM options when starting JVM in a container, for example:
```
docker run -e JAVA_OPTS="-Xms1g -Xmx2g" ...
```
The JVM will be started with `1g` amount of memory and will be able to use a maximum of `2g` amount of memory.


### CPU
**Ubuntu 22.04, CPU (OpenMP), Java Bindings**

1. Build the docker image from the Dockerfile in this repository.
```
docker build -t ai-serving-cpu -f Dockerfile ..
```

2. Run the Docker image
```
docker run -it -v {local_writable_directory}:/opt/ai-serving -p {local_http_port}:9090 -p {local_grpc_port}:9091 ai-serving-cpu
```

## CUDA
**Ubuntu 22.04, CUDA 12.1.1 and CuDNN 8**

1. Build the docker image from the Dockerfile in this repository.
```
docker build -t ai-serving-cuda -f Dockerfile.cuda ..
```

2. Run the Docker image
```
docker run --gpus all -it -v {local_writable_directory}:/opt/ai-serving -p {local_http_port}:9090 -p {local_grpc_port}:9091 ai-serving-cuda
```
or
```
nvidia-docker run -it -v {local_writable_directory}:/opt/ai-serving -p {local_http_port}:9090 -p {local_grpc_port}:9091 ai-serving-cuda
```

