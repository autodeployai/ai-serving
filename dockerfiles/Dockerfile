# --------------------------------------------------------------
# Copyright (c) AutoDeployAI. All rights reserved.
# Licensed under the Apache License, Version 2.0 (the "License").
# --------------------------------------------------------------

FROM ubuntu:16.04
MAINTAINER AutoDeployAI "autodeploy.ai@gmail.com"

ARG AI_SERVING_REPO=https://github.com/autodeployai/ai-serving
ARG AI_SERVING_BRANCH=master

RUN apt-get update &&\
  apt-get install -y sudo git bash &&\
  rm -rf /var/lib/apt/lists/*

WORKDIR /code
ENV \
  LANG=C.UTF-8 \
  JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64 \
  PATH=/code/sbt/bin:${PATH}

# Prepare ai-serving repository and build it with onnxruntime default CPU binaries harvested from maven central 
RUN git clone --single-branch --branch ${AI_SERVING_BRANCH} --recursive ${AI_SERVING_REPO} ai-serving &&\
  /bin/sh ai-serving/dockerfiles/scripts/install_common_deps.sh &&\
  cd ai-serving &&\
  sbt assembly &&\
  mkdir -p /ai-serving &&\
  cp target/scala-2.13/*.jar /ai-serving &&\
  cd /ai-serving &&\
  ln -s ai-serving-assembly-*.jar ai-serving-assembly.jar &&\
  rm -rf /code/* &&\
  rm -rf ~/.cache && rm -rf ~/.ivy2 && rm -rf ~/.sbt && rm -rf /tmp/*

WORKDIR /ai-serving
ENTRYPOINT exec java $JAVA_OPTS -jar ai-serving-assembly.jar

