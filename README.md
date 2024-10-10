# AI-Serving

Serving AI/ML models in the open standard formats [PMML](http://dmg.org/pmml/v4-4-1/GeneralStructure.html) and [ONNX](https://onnx.ai/) with both HTTP (REST API) and gRPC endpoints.

## Table of Contents

- [Features](#features)
- [Prerequisites](#prerequisites) 
- [Installation](#installation)
    - [Install using Docker](#install-using-docker)
    - [Install from Source](#install-from-source)
        - [Install SBT](#install-sbt)
        - [Build Assembly](#build-assembly)
        - [Start Server](#start-server)
        - [Server Configurations](#server-configurations)
- [PMML](#pmml)
- [ONNX](#onnx)
    - [Advanced ONNX Runtime Configuration](#advanced-onnx-runtime-configuration)
        - [Build ONNX Runtime](#build-onnx-runtime)
        - [Load ONNX Runtime](#load-onnx-runtime)
- [REST APIs](#rest-apis)
    - [Validate API](#1-validate-api)
    - [Deploy API](#2-deploy-api)
    - [Model Metadata API](#3-model-metadata-api)
    - [Predict API](#4-predict-api)
- [gRPC APIs](#grpc-apis)
- [Examples](#examples)
    - [Python](#python)
      - [Serving PMML models with AI-Serving](examples/AIServingIrisXGBoostPMMLModel.ipynb) 
      - [Serving ONNX models with AI-Serving](examples/AIServingMnistOnnxModel.ipynb)
    - [Curl](#curl)
      - [Scoring PMML models](#scoring-pmml-models)
      - [Scoring ONNX models](#scoring-onnx-models)
- [Deploy and Manage AI/ML models at scale](#deploy-and-manage-aiml-models-at-scale)
- [Support](#support)
- [License](#license)

## Features

AI Serving is a flexible, high-performance inferencing system for machine learning and deep learning models, designed for production environments. AI Serving provides out-of-the-box integration with PMML and ONNX models, but can be easily extended to serve other formats of models.


## Prerequisites

* Java >= 1.8

## Installation

### Install using Docker
The easiest and most straight-forward way of using AI-Serving is with [Docker images](dockerfiles).

### Install from Source

#### Install SBT

The [`sbt`](https://www.scala-sbt.org/) build system is required. After sbt installed, clone this repository, then change into the repository root directory:
```bash
cd REPO_ROOT
```

#### Build Assembly

AI-Serving depends on [ONNX Runtime](https://github.com/microsoft/onnxruntime) to support ONNX models, and the default CPU accelerator (OpenMP) is used for ONNX Runtime:
```bash

sbt clean assembly
```

Set the property `-Dgpu=true` to use the GPU accelerator (CUDA) for [ONNX Runtime](https://github.com/microsoft/onnxruntime):
```bash
sbt -Dgpu=true clean assembly
```

Run `set test in assembly := {}` to disable unit tests if you want to skip them when generating an assembly jar:
```bash
sbt -Dgpu=true 'set test in assembly := {}' clean assembly
```

An assembly jar will be generated:
```bash
$REPO_ROOT/target/scala-2.13/ai-serving-assembly-<version>.jar or ai-serving-gpu-assembly-<version>.jar
```

#### Start Server

Simply run with the default CPU backend for ONNX models:
```bash
java -jar ai-serving-assembly-<version>.jar
```

GPU backend for ONNX models:
```bash
java -Donnxruntime.backend=cuda -jar ai-serving-gpu-assembly-<version>.jar
```
Several available execution backends: TensorRT, DirectML, Dnnl and so on. See [Advanced ONNX Runtime Configuration](#advanced-onnx-runtime-configuration) for details.

#### Server Configurations

By default, the HTTP endpoint is listening on `http://0.0.0.0:9090/`, and the gRPC port is `9091`. You can customize those options that are defined in the [`application.conf`](src/main/resources/application.conf). There are several ways to override the default options, one is to create a new config file based on the default one, then:

```bash
java -Dconfig.file=/path/to/config-file -jar ai-serving-assembly-<version>.jar
```

Another is to override each by setting Java system property, for example:

```bash
java -Dservice.http.port=9000 -Dservice.grpc.port=9001 -Dservice.home="/path/to/writable-directory" -jar ai-serving-assembly-<version>.jar
```

AI-Serving is designed to be persistent or recoverable, so it needs a place to save all served models, that is specified by the property `service.home` that takes `/opt/ai-serving` as default, and the directory must be writable.

## PMML

Integrates [PMML4S](https://github.com/autodeployai/pmml4s) to score PMML models. PMML4S is a lightweight, clean and efficient implementation based on the [PMML](http://dmg.org/) specification from 2.0 through to the latest 4.4.1. 

PMML4S is written in pure Scala that running in JVM, AI-Serving needs no special configurations to support PMML models.

## ONNX

Leverages [ONNX Runtime](https://github.com/microsoft/onnxruntime) to make predictions for ONNX models. ONNX Runtime is a performance-focused inference engine for ONNX models.

ONNX Runtime is implemented in C/C++, and AI-Serving calls ONNX Runtime Java API to support ONNX models. ONNX Runtime can support various architectures with multiple hardware accelerators, refer to the table on [aka.ms/onnxruntime](https://microsoft.github.io/onnxruntime/) for details.

Since both CPU and GPU binaries of X64 have been distributed to the [Maven Central](https://mvnrepository.com/artifact/com.microsoft.onnxruntime), AI-Serving depends on them directly now, the CPU is used by default, and you can switch to GPU via the property `-Dgpu=true` defined above. 

If you depend on other different OS/architectures or hardware accelerators, refer to the next topic [Advanced ONNX Runtime Configuration](#advanced-onnx-runtime-configuration), otherwise, ignore it.

### Advanced ONNX Runtime Configuration

#### Build ONNX Runtime
   
You need to build both native libraries `JNI shared library` and `onnxruntime shared library` for your OS/Architecture: 
   
Please, refer to the [onnxtime build instructions](https://github.com/microsoft/onnxruntime/blob/master/BUILD.md), and the `--build_java` option always must be specified.

#### Load ONNX Runtime
    
Please, see [Build Output](https://github.com/microsoft/onnxruntime/tree/master/java#build-output) lists all generated outputs. Explicitly specify the path to the shared library when starting AI-Serving:
  ```bash
  java -Donnxruntime.backend=execution_backend -Donnxruntime.native.onnxruntime4j_jni.path=/path/to/onnxruntime4j_jni -Donnxruntime.native.onnxruntime.path=/path/to/onnxruntime -jar ai-serving-assembly-<version>.jar
  ```

## REST APIs

- [Validate API](#1-validate-api)
- [Deploy API](#2-deploy-api)
- [Model Metadata API](#3-model-metadata-api)
- [Predict API](#4-predict-api)

When an error occurs, all APIs will return a JSON object as follows:
```
{
  "error": <an error message string>
}
```

### 1. Validate API

#### URL:
```
PUT http://host:port/v1/validate
```

#### Request:
Model with `Content-Type` that tells the server which format to handle:
 * `Content-Type: application/xml`, or `Content-Type: text/xml`, the input is treated as a PMML model.
 * `Content-Type: application/octet-stream`, `application/vnd.google.protobuf` or `application/x-protobuf`, the input is processed as an ONNX model.
 
If no `Content-Type` specified, the server can probe the content type from the input entity, but it could fail.

#### Response:
Model metadata includes the model type, input list, output list, and so on.
```
{
  "type": <model_type>
  "inputs": [
    {
      "name": <input_name1>,
      "type": <field_type>,
      ...
    },
    {
      "name": <input_name2>,
      "type": <field_type>,
      ...
    },
    ...
  ],
  "outputs": [
    {
      "name": <output_name1>,
      "type": <field_type>,
      ...
    },
    {
      "name": <output_name2>,
      "type": <field_type>,
      ...
    },
    ...
  ],
  ...
}
```

### 2. Deploy API

#### Deployment URL:
```
PUT http://host:port/v1/models/${MODEL_NAME}
```

#### Request:
Model with `Content-Type`, see validation request above for details.

#### Response:
```
{
  // The specified servable name
  "name": <model_name>
  
  // The deployed version starts from 1
  "version": <model_version>
}
```

#### Undeployment URL:
```
DELETE http://host:port/v1/models/${MODEL_NAME}
```

#### Response:
```
204 No Content
```

### 3. Model Metadata API

#### URL:
```
GET http://host:port/v1/models[/${MODEL_NAME}[/versions/${MODEL_VERSION}]]
```
* If `/${MODEL_NAME}/versions/${MODEL_VERSION}` is missing, all models are returned.
* If `/versions/${MODEL_VERSION}` is missing, all versions of the specified model are returned.
* Otherwise, only the specified version and model is returnd.

#### Response:

```
// All models are returned from GET http://host:port/v1/models
[
  {
    "name": <model_name1>,
    "versions": [
      {
        "version": 1,
        ...
      },
      {
        "version": 2,
        ...
      },
      ...
    ]
  },
  {
    "name": <model_name2>,
    "versions": [
      {
        "version": 1,
        ...
      },
      {
        "version": 2,
        ...
      },
      ...
    ]
  },
  ...
]
```

```
// All versions of the specified model are returned from GET http://host:port/v1/models/${MODEL_NAME}
{
  "name": <model_name>,
  "versions": [
    {
      "version": 1,
      ...
    },
    {
      "version": 2,
      ...
    },
    ...
  ]
}
```

```
// The specified version and model is returned from http://host:port/v1/models/${MODEL_NAME}/versions/${MODEL_VERSION}
{
  "name": <model_name>,
  "versions": [
    {
      "version": <model_version>,
      ...
    }
  ]
}
```


### 4. Predict API

#### URL:
```
POST http://host:port/v1/models/${MODEL_NAME}[/versions/${MODEL_VERSION}]
```
/versions/${MODEL_VERSION} is optional. If omitted the latest version is used.

#### Request:
The request body could have two formats: JSON and binary, the HTTP header `Content-Type` tells the server which format to handle and thus it is required for all requests.

* `Content-Type: application/json`. The request body must be a JSON object formatted as follows:
  ```
  {
    "X": {
      "records": [
        {
          "input_name1": <value>|<(nested)list>,
          "input_name2": <value>|<(nested)list>,
          ...
        },
        {
          "input_name1": <value>|<(nested)list>,
          "input_name2": <value>|<(nested)list>,
          ...
        },
        ...
      ],
      "columns": [ "input_name1", "input_name2", ... ],
      "data": [ 
        [ <value>|<(nested)list>, <value>|<(nested)list>, ... ], 
        [ <value>|<(nested)list>, <value>|<(nested)list>, ... ], 
        ... 
      ]
    },
    // Output filters to specify which output fields need to be returned.
    // If the list is empty, all outputs will be included.
    "filter": <list>
  }
  ```
  The `X` can take more than one records, as you see above, there are two formats supported. You could use any one, usually the `split` format is smaller for multiple records.
  - `records` : list like [{column -> value}, … , {column -> value}]
  - `split` : dict like {columns -> [columns], data -> [values]}

* `Content-Type: application/octet-stream`, `application/vnd.google.protobuf` or `application/x-protobuf`. 
  
  The request body must be the protobuf message [`PredictRequest`](https://github.com/autodeployai/ai-serving/blob/master/src/main/protobuf/ai-serving.proto#L152) of gRPC API, besides of those common scalar values, it can use the standard [`onnx.TensorProto`](https://github.com/autodeployai/ai-serving/blob/master/src/main/protobuf/onnx-ml.proto#L304) value directly.

* Otherwise, an error will be returned.

#### Response:
The server always return the same type as your request.

* For the JSON format. The response body is a JSON object formatted as follows:
```
{
  "result": {
    "records": [
      {
        "output_name1": <value>|<(nested)list>,
        "output_name2": <value>|<(nested)list>,
        ...
      },
      {
        "output_name1": <value>|<(nested)list>,
        "output_name2": <value>|<(nested)list>,
        ...
      },
      ...
    ],
    "columns": [ "output_name1", "output_name2", ... ],
    "data": [ 
      [ <value>|<(nested)list>, <value>|<(nested)list>, ... ], 
      [ <value>|<(nested)list>, <value>|<(nested)list>, ... ], 
      ... 
    ]
  }
}
```
* For the binary format. The response body is the protobuf message [`PredictResponse`](https://github.com/autodeployai/ai-serving/blob/master/src/main/protobuf/ai-serving.proto#L164) of gRPC API.

Generally speaking, the binary payload has better latency, especially for the big tensor value for ONNX models, while the JSON format is easy for human readability.

## gRPC APIs
Please, refer to the protobuf file [`ai-serving.proto`](src/main/protobuf/ai-serving.proto) for details. You could generate your client and make a gRPC call to it using your favorite language. To learn more about how to generate the client code and call to the server, please refer to [the tutorials of gRPC](https://grpc.io/docs/tutorials/).

## Examples

### Python
- [Serving ONNX models with AI-Serving](examples/AIServingMnistOnnxModel.ipynb)
- [Serving PMML models with AI-Serving](examples/AIServingIrisXGBoostPMMLModel.ipynb) 

### Curl
- [Scoring PMML models](#scoring-pmml-models)
- [Scoring ONNX models](#scoring-onnx-models)

#### Start AI-Serving
We will use Docker to run the AI-Serving:
```bash
docker pull autodeployai/ai-serving:latest
docker run --rm -it -v /opt/ai-serving:/opt/ai-serving -p 9090:9090 -p 9091:9091 autodeployai/ai-serving:latest

16:06:47.722 INFO  AI-Serving-akka.actor.default-dispatcher-5 akka.event.slf4j.Slf4jLogger             Slf4jLogger started
16:06:47.833 INFO  main            ai.autodeploy.serving.AIServer$          Predicting thread pool size: 16
16:06:48.305 INFO  main            a.autodeploy.serving.protobuf.GrpcServer AI-Serving grpc server started, listening on 9091
16:06:49.433 INFO  main            ai.autodeploy.serving.AIServer$          AI-Serving http server started, listening on http://0.0.0.0:9090/
```

#### Make REST API calls to AI-Serving
In a different terminal, run `cd $REPO_ROOT/src/test/resources`, use the `curl` tool to make REST API calls.


#### Scoring PMML models
We will use the `Iris` decision tree model [single_iris_dectree.xml](http://dmg.org/pmml/pmml_examples/KNIME_PMML_4.1_Examples/single_iris_dectree.xml) to see REST APIs in action.

* ##### Validate the PMML model
```bash
curl -X PUT --data-binary @single_iris_dectree.xml -H "Content-Type: application/xml"  http://localhost:9090/v1/validate
{
  "algorithm": "TreeModel",
  "app": "KNIME",
  "appVersion": "2.8.0",
  "copyright": "KNIME",
  "formatVersion": "4.1",
  "functionName": "classification",
  "outputs": [
    {
      "name": "predicted_class",
      "optype": "nominal",
      "type": "string"
    },
    {
      "name": "probability",
      "optype": "continuous",
      "type": "real"
    },
    {
      "name": "probability_Iris-setosa",
      "optype": "continuous",
      "type": "real"
    },
    {
      "name": "probability_Iris-versicolor",
      "optype": "continuous",
      "type": "real"
    },
    {
      "name": "probability_Iris-virginica",
      "optype": "continuous",
      "type": "real"
    },
    {
      "name": "node_id",
      "optype": "nominal",
      "type": "string"
    }
  ],
  "inputs": [
    {
      "name": "sepal_length",
      "optype": "continuous",
      "type": "double",
      "values": "[4.3,7.9]"
    },
    {
      "name": "sepal_width",
      "optype": "continuous",
      "type": "double",
      "values": "[2.0,4.4]"
    },
    {
      "name": "petal_length",
      "optype": "continuous",
      "type": "double",
      "values": "[1.0,6.9]"
    },
    {
      "name": "petal_width",
      "optype": "continuous",
      "type": "double",
      "values": "[0.1,2.5]"
    }
  ],
  "runtime": "PMML4S",
  "serialization": "pmml",
  "targets": [
    {
      "name": "class",
      "optype": "nominal",
      "type": "string",
      "values": "Iris-setosa,Iris-versicolor,Iris-virginica"
    }
  ],
  "type": "PMML"
}
```

* ##### Deploy the PMML model
```bash
curl -X PUT --data-binary @single_iris_dectree.xml -H "Content-Type: application/xml"  http://localhost:9090/v1/models/iris
{
  "name": "iris",
  "version": 1
}
```

* ##### Get metadata of the PMML model
```bash
curl -X GET http://localhost:9090/v1/models/iris
{
  "createdAt": "2020-04-23T06:29:32",
  "id": "56fa4917-e904-4364-9c15-4c87d84ec2c4",
  "latestVersion": 1,
  "name": "iris",
  "updateAt": "2020-04-23T06:29:32",
  "versions": [
    {
      "algorithm": "TreeModel",
      "app": "KNIME",
      "appVersion": "2.8.0",
      "copyright": "KNIME",
      "createdAt": "2020-04-23T06:29:32",
      "formatVersion": "4.1",
      "functionName": "classification",
      "hash": "fc44c33123836be368d3f24829360020",
      "outputs": [
        {
          "name": "predicted_class",
          "optype": "nominal",
          "type": "string"
        },
        {
          "name": "probability",
          "optype": "continuous",
          "type": "real"
        },
        {
          "name": "probability_Iris-setosa",
          "optype": "continuous",
          "type": "real"
        },
        {
          "name": "probability_Iris-versicolor",
          "optype": "continuous",
          "type": "real"
        },
        {
          "name": "probability_Iris-virginica",
          "optype": "continuous",
          "type": "real"
        },
        {
          "name": "node_id",
          "optype": "nominal",
          "type": "string"
        }
      ],
      "inputs": [
        {
          "name": "sepal_length",
          "optype": "continuous",
          "type": "double",
          "values": "[4.3,7.9]"
        },
        {
          "name": "sepal_width",
          "optype": "continuous",
          "type": "double",
          "values": "[2.0,4.4]"
        },
        {
          "name": "petal_length",
          "optype": "continuous",
          "type": "double",
          "values": "[1.0,6.9]"
        },
        {
          "name": "petal_width",
          "optype": "continuous",
          "type": "double",
          "values": "[0.1,2.5]"
        }
      ],
      "runtime": "PMML4S",
      "serialization": "pmml",
      "size": 3497,
      "targets": [
        {
          "name": "class",
          "optype": "nominal",
          "type": "string",
          "values": "Iris-setosa,Iris-versicolor,Iris-virginica"
        }
      ],
      "type": "PMML",
      "version": 1
    }
  ]
}
```

* ##### Predict the PMML model using the JSON payload in `records`
```bash
curl -X POST -d '{"X": [{"sepal_length": 5.1, "sepal_width": 3.5, "petal_length": 1.4, "petal_width": 0.2}]}' -H "Content-Type: application/json"  http://localhost:9090/v1/models/iris
{
  "result": [
    {
      "node_id": "1",
      "probability_Iris-setosa": 1.0,
      "predicted_class": "Iris-setosa",
      "probability_Iris-virginica": 0.0,
      "probability_Iris-versicolor": 0.0,
      "probability": 1.0
    }
  ]
}
```

* ##### Predict the PMML model using the JSON payload in `split` with filters
```bash
curl -X POST -d '{"X": {"columns": ["sepal_length", "sepal_width", "petal_length", "petal_width"],"data":[[5.1, 3.5, 1.4, 0.2], [7, 3.2, 4.7, 1.4]]}, "filter": ["predicted_class"]}' -H "Content-Type: application/json"  http://localhost:9090/v1/models/iris
{
  "result": {
    "columns": [
      "predicted_class"
    ],
    "data": [
      [
        "Iris-setosa"
      ],
      [
        "Iris-versicolor"
      ]
    ]
  }
}
```

#### Scoring ONNX models
We will use the pre-trained [MNIST Handwritten Digit Recognition ONNX Model](https://github.com/onnx/models/tree/master/vision/classification/mnist) to see REST APIs in action. 

* ##### Validate the ONNX model
```bash
curl -X PUT --data-binary @mnist.onnx -H "Content-Type: application/octet-stream"  http://localhost:9090/v1/validate
{
  "outputs": [
    {
      "name": "Plus214_Output_0",
      "shape": [
        1,
        10
      ],
      "type": "tensor(float)"
    }
  ],
  "inputs": [
    {
      "name": "Input3",
      "shape": [
        1,
        1,
        28,
        28
      ],
      "type": "tensor(float)"
    }
  ],
  "runtime": "ONNX Runtime",
  "serialization": "onnx",
  "type": "ONNX"
}
```

* ##### Deploy the ONNX model
```bash
curl -X PUT --data-binary @mnist.onnx -H "Content-Type: application/octet-stream"  http://localhost:9090/v1/models/mnist
{
  "name": "mnist",
  "version": 1
}
```

* ##### Get metadata of the ONNX model
```bash
curl -X GET http://localhost:9090/v1/models/mnist
{
  "createdAt": "2020-04-16T15:18:18",
  "id": "850bf345-5c4c-4312-96c8-6ee715113961",
  "latestVersion": 1,
  "name": "mnist",
  "updateAt": "2020-04-16T15:18:18",
  "versions": [
    {
      "createdAt": "2020-04-16T15:18:18",
      "hash": "104617a683b4e62469478e07e1518aaa",
      "outputs": [
        {
          "name": "Plus214_Output_0",
          "shape": [
            1,
            10
          ],
          "type": "tensor(float)"
        }
      ],
      "inputs": [
        {
          "name": "Input3",
          "shape": [
            1,
            1,
            28,
            28
          ],
          "type": "tensor(float)"
        }
      ],
      "runtime": "ONNX Runtime",
      "serialization": "onnx",
      "size": 26454,
      "type": "ONNX",
      "version": 1
    }
  ]
}
```

* ##### Predict the ONNX model using the REST payload in `records`
```bash
curl -X POST -d @mnist_request_0.json -H "Content-Type: application/json" http://localhost:9090/v1/models/mnist
{
  "result": [
    {
      "Plus214_Output_0": [
        [
          975.6703491210938,
          -618.7241821289062,
          6574.5654296875,
          668.0283203125,
          -917.2710571289062,
          -1671.6361083984375,
          -1952.7598876953125,
          -61.54957580566406,
          -777.1764526367188,
          -1439.5316162109375
        ]
      ]
    }
  ]
}
```

* ##### Predict the ONNX model using the binary payload
```bash
curl -X POST --data-binary @mnist_request_0.pb -o response_0.pb -H "Content-Type: application/octet-stream" http://localhost:9090/v1/models/mnist
```

Save the binary response to `response_0.pb` that is in the `protobuf` format, an instance of `PredictResponse` message, you could use the generated client from `ai-serving.proto` to read it.

Note, the content type of `predict` request must be specified explicitly and take one of four candidates. An incorrect request URL or body returns an HTTP error status.
```bash
curl -i -X POST -d @mnist_request_0.json  http://localhost:9090/v1/models/mnist
HTTP/1.1 400 Bad Request
Server: akka-http/10.1.11
Date: Sun, 19 Apr 2020 06:25:25 GMT
Connection: close
Content-Type: application/json
Content-Length: 92

{"error":"Prediction request takes unknown content type: application/x-www-form-urlencoded"}
```

## Deploy and Manage AI/ML models at scale
See the [DaaS](https://www.autodeployai.com/) system that deploys AI/ML models in production at scale on Kubernetes.

## Support
If you have any questions about the _AI-Serving_ library, please open issues on this repository.

Feedback and contributions to the project, no matter what kind, are always very welcome. 

## License
_AI-Serving_ is licensed under [APL 2.0](http://www.apache.org/licenses/LICENSE-2.0).
