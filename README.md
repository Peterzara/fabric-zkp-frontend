# fabric-zkp-frontend
## Introduction
`greeting-common` contains gRPC interfaces
`greeting-service` contains the gRPC implementation

## Getting Started
1. clone the repo and open in IntelliJ (Java 17)
2. download mvn dependencies
3. spin up backend blockchain network
4. run class `GrpcSpringBootExampleApplication`, which is the entry point of application
5. test endpoints (install `grpcurl` first):
```bash
grpcurl --plaintext -d '{"message": "test"}' localhost:9090 com.techprimers.grpc.WorkloadService/initBlockchain
grpcurl --plaintext -d '{"message": "test"}' localhost:9090 com.techprimers.grpc.WorkloadService/queryAll
grpcurl --plaintext -d '{"message": "test"}' localhost:9090 com.techprimers.grpc.WorkloadService/create
```
