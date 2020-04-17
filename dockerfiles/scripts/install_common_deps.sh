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
        openjdk-8-jdk \
        ca-certificates-java 
rm -rf /var/lib/apt/lists/*

# Dependencies: sbt
sudo wget --quiet https://piccolo.link/sbt-1.3.6.tgz
tar zxf sbt-1.3.6.tgz
rm -rf sbt-1.3.6.tgz

