#!/bin/bash

DEBIAN_FRONTEND=noninteractive
apt-get update && apt-get install -y --no-install-recommends \
        wget \
        zip \
        unzip \
        ca-certificates \
        build-essential \
        curl \
        libcurl4-openssl-dev \
        libssl-dev \
        python3-dev &&\
        rm -rf /var/lib/apt/lists/*

# Dependencies: gradle
sudo wget --quiet https://services.gradle.org/distributions/gradle-6.3-bin.zip
unzip gradle-6.3-bin.zip
rm -rf gradle-6.3-bin.zip

# Dependencies: cmake
sudo wget --quiet https://github.com/Kitware/CMake/releases/download/v3.14.3/cmake-3.14.3-Linux-x86_64.tar.gz
tar zxf cmake-3.14.3-Linux-x86_64.tar.gz
rm -rf cmake-3.14.3-Linux-x86_64.tar.gz

