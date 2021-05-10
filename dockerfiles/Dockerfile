# --------------------------------------------------------------
# Copyright (c) AutoDeployAI. All rights reserved.
# Licensed under the Apache License, Version 2.0 (the "License").
# --------------------------------------------------------------

FROM ubuntu:18.04
MAINTAINER AutoDeployAI "autodeploy.ai@gmail.com"

ADD . /code

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update &&\
  apt-get install -y --no-install-recommends \
  wget \
  zip \
  unzip \
  ca-certificates \
  build-essential \
  curl \
  libcurl4-openssl-dev \
  libssl-dev \
  libgomp1 \
  openjdk-11-jdk \
  ca-certificates-java

RUN  wget -q -O /tmp/sbt-1.3.6.tgz https://github.com/sbt/sbt/releases/download/v1.3.6/sbt-1.3.6.tgz &&\
  tar -zxf /tmp/sbt-1.3.6.tgz --strip=1 -C /usr

# Harvest both PMML4S and ONNXRuntime(CPU) from maven central
RUN cd /code &&\
  sbt assembly


FROM ubuntu:18.04

WORKDIR /ai-serving

ENV \
  LANG=C.UTF-8 \
  JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64

RUN apt-get update &&\
  apt-get install -y libgomp1 openjdk-11-jre ca-certificates-java &&\
  rm -rf /var/lib/apt/lists/*

COPY --from=0 /code/target/scala-2.13/*.jar .

RUN ln -s ai-serving-assembly-*.jar ai-serving-assembly.jar

ENTRYPOINT exec java $JAVA_OPTS -jar ai-serving-assembly.jar

