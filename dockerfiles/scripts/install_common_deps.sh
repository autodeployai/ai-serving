#!/bin/bash
DEBIAN_FRONTEND=noninteractive
apt-get update && apt-get install -y --no-install-recommends \
        wget \
        zip \
        ca-certificates \
        build-essential \
        curl \
        libcurl4-openssl-dev \
        libssl-dev \
        openjdk-8-jdk \
        ca-certificates-java \
        && rm -rf /var/lib/apt/lists/*

# Dependencies: sbt
sudo wget --quiet https://piccolo.link/sbt-1.3.6.tgz
tar zxf sbt-1.3.6.tgz
rm -rf sbt-1.3.6.tgz

# Dependencies: cmake
sudo wget --quiet https://github.com/Kitware/CMake/releases/download/v3.14.3/cmake-3.14.3-Linux-x86_64.tar.gz
tar zxf cmake-3.14.3-Linux-x86_64.tar.gz
rm -rf cmake-3.14.3-Linux-x86_64.tar.gz
